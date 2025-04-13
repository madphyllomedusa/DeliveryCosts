package etu.ru.deliverycosts.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import etu.ru.deliverycosts.model.entity.Delivery;
import etu.ru.deliverycosts.model.entity.Product;
import etu.ru.deliverycosts.model.entity.ProductPrice;
import etu.ru.deliverycosts.repository.DeliveryRepository;
import etu.ru.deliverycosts.repository.ProductRepository;
import etu.ru.deliverycosts.util.samokat.SamokatCategory;
import etu.ru.deliverycosts.util.samokat.SamokatCategoryWithProductsDto;
import etu.ru.deliverycosts.util.samokat.SamokatProductDto;
import etu.ru.deliverycosts.util.samokat.SamokatSubcategoryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class SamokatUpdateService {

    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final DeliveryRepository deliveryRepository;
    private final ImageCaptchaSolver imageCaptchaSolver;

    /**
     * Метод, который автоматически раз в час:
     * 1) Открывает https://samokat.ru/
     * 2) Перехватывает JSON с нужных API-запросов
     * 3) Парсит и сохраняет товары
     */
@Scheduled(cron = "0 0 * * * ?")
public void updateSamokatData() {
    log.info("Начало обновления данных Samokat...");

    try (Playwright playwright = Playwright.create()) {
        Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of(
                    "--disable-blink-features=AutomationControlled",
                    "--disable-web-security",
                    "--disable-dev-shm-usage"
                )));

        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .setExtraHTTPHeaders(Map.of(
                        "Accept", "application/json",
                        "Accept-Language", "ru-RU,ru;q=0.9",
                        "Origin", "https://samokat.ru",
                        "Referer", "https://samokat.ru/"
                ))
                .setViewportSize(1920, 1080));

        context.addInitScript(
                "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });" +
                "window.chrome = { runtime: {} };");

        Page page = context.newPage();

        navigateWithRetry(page, "https://samokat.ru/", 3);

        String categoriesJson = fetchApiData(page,
                "https://api-web.samokat.ru/v2/showcases/5636bcd8-07dc-4752-93f5-21c3661fedee/categories/list");

        List<SamokatCategory> categories = objectMapper.readValue(categoriesJson,
                new TypeReference<List<SamokatCategory>>() {});

        for (SamokatCategory category : categories) {
            String categoryUrl = String.format(
                    "https://api-web.samokat.ru/v2/categories/%s?showcaseUuid=5636bcd8-07dc-4752-93f5-21c3661fedee",
                    category.getId());

            String categoryJson = fetchApiData(page, categoryUrl);
            SamokatCategoryWithProductsDto categoryDetail = objectMapper.readValue(
                    categoryJson, SamokatCategoryWithProductsDto.class);

            parseAndSaveCategoryDetail(categoryDetail);
        }

    } catch (Exception e) {
        log.error("Критическая ошибка: {}", e.getMessage(), e);
    }
}

private String fetchApiData(Page page, String url) throws Exception {
    AtomicReference<Response> apiResponse = new AtomicReference<>();
    page.onResponse(response -> {
        if (response.url().equals(url) && response.status() == 200) {
            apiResponse.set(response);
        }
    });

    page.mouse().move(300, 300);
    page.waitForTimeout(1500 + (int)(Math.random() * 2000));

    page.evaluate("url => fetch(url, {"
        + "method: 'GET',"
        + "headers: {"
        + "  'X-Client-Type': 'web',"
        + "  'X-Device-Id': 'generated-" + UUID.randomUUID() + "'"
        + "}"
        + "})", url);

    long startTime = System.currentTimeMillis();
    long timeout = 30000; // 30 секунд

    while (apiResponse.get() == null) {
        if (System.currentTimeMillis() - startTime > timeout) {
            throw new RuntimeException("Timeout waiting for API response");
        }
        page.waitForTimeout(500);
    }

    return apiResponse.get().text();
}

private void navigateWithRetry(Page page, String url, int maxRetries) {
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));

            handleCaptchaIfPresent(page);

            if(page.locator("body").isVisible()) return;

        } catch (Exception e) {
            log.warn("Попытка {}: Ошибка навигации: {}", attempt, e.getMessage());
            page.reload();
        }
    }
    throw new RuntimeException("Не удалось загрузить страницу после " + maxRetries + " попыток");
}

private void handleCaptchaIfPresent(Page page) {
    try {
        Locator captchaLocator = page.locator("img[src*='captcha']");
        if(captchaLocator.count() > 0) {
            log.info("Обнаружена капча, решаем...");
            byte[] imageBytes = captchaLocator.first().screenshot();
            String solution = imageCaptchaSolver.solveCaptcha(imageBytes);
            page.fill("input[name='captcha']", solution);
            page.click("button[type='submit']");
            page.waitForSelector("img[src*='captcha']",
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED));
        }
    } catch (Exception e) {
        log.error("Ошибка обработки капчи: {}", e.getMessage());
    }
}

    private void parseAndSaveCategoryDetail(SamokatCategoryWithProductsDto detailCat) {
        Delivery samokatDelivery = deliveryRepository.findByName("Samokat")
                .orElseGet(() -> {
                    Delivery d = new Delivery();
                    d.setName("Samokat");
                    d.setUrl("https://samokat.ru/");
                    return deliveryRepository.save(d);
                });

        log.info("Обработка детальной категории [{}]: {}", detailCat.getUuid(), detailCat.getName());
        if (detailCat.getCategories() == null) return;

        for (SamokatSubcategoryDto sub : detailCat.getCategories()) {
            log.info("Подкатегория: {} (uuid={})", sub.getName(), sub.getUuid());
            List<SamokatProductDto> products = sub.getProducts();
            if (products == null) continue;

            for (SamokatProductDto p : products) {
                saveProduct(p, samokatDelivery);
            }
        }
    }

    private void saveProduct(SamokatProductDto dto, Delivery samokatDelivery) {
        Product product = new Product();
        product.setName(dto.getName());
        product.setDescription("Samokat UUID: " + dto.getUuid());
        List<ProductPrice> prices = new ArrayList<>();

        if (dto.getPrices() != null) {
            if (dto.getPrices().getCurrent() != null) {
                ProductPrice priceCurrent = new ProductPrice();
                priceCurrent.setPrice(convertKopecksToRubles(dto.getPrices().getCurrent()));
                priceCurrent.setService(samokatDelivery);
                priceCurrent.setProduct(product);
                prices.add(priceCurrent);
            }
            if (dto.getPrices().getPickup() != null) {
                ProductPrice pricePickup = new ProductPrice();
                pricePickup.setPrice(convertKopecksToRubles(dto.getPrices().getPickup()));
                pricePickup.setService(samokatDelivery);
                pricePickup.setProduct(product);
                prices.add(pricePickup);
            }
        }
        product.setPrices(prices);
        productRepository.save(product);
        log.info("Сохранён товар: {} (uuid={})", product.getName(), dto.getUuid());
    }

    private BigDecimal convertKopecksToRubles(Integer kopecks) {
        if (kopecks == null) return BigDecimal.ZERO;
        return BigDecimal.valueOf(kopecks).divide(BigDecimal.valueOf(100));
    }
}
