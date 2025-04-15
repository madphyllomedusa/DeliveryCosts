package etu.ru.deliverycosts.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import etu.ru.deliverycosts.model.entity.Delivery;
import etu.ru.deliverycosts.model.entity.Product;
import etu.ru.deliverycosts.model.entity.ProductPrice;
import etu.ru.deliverycosts.repository.DeliveryRepository;
import etu.ru.deliverycosts.repository.ProductRepository;
import etu.ru.deliverycosts.util.samokat.SamokatPrices;
import etu.ru.deliverycosts.util.samokat.SamokatProductDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                .setHeadless(false)
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

            // Скрываем navigator.webdriver
            context.addInitScript(
                    "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });" +
                            "window.chrome = { runtime: {} };");

            Page page = context.newPage();

            // 2) Увеличим таймауты на странице
            page.setDefaultTimeout(60000); // 60 секунд

            // Переходим на главную, обрабатываем капчу (если есть)
            navigateWithRetry(page, "https://samokat.ru/", 3);

            // Небольшая пауза, чтобы всё точно подгрузилось
            page.waitForTimeout(5000);

            // Пример – парсинг ссылок категорий
            List<String> categoryUrls = parseCategoryLinksFromDom(page);
            log.info("Найдено категорий: {}", categoryUrls.size());

            for (String catUrl : categoryUrls) {
                log.info("Переходим в категорию: {}", catUrl);
                page.navigate(catUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                handleCaptchaIfPresent(page);

                // Явно ждём появления карточек товаров (до 60 сек)
                page.waitForSelector(".ProductCard_root__OCLMl",
                        new Page.WaitForSelectorOptions().setTimeout(60000));

                // Парсим товары
                List<SamokatProductDto> products = parseProductsFromDom(page);
                saveProducts(products);

                // Можно возвращаться назад или заново заходить на главную
                page.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                handleCaptchaIfPresent(page);
            }

            browser.close();
        } catch (Exception e) {
            log.error("Критическая ошибка: {}", e.getMessage(), e);
        }
    }

    /**
     * Пример получения ссылок на категории из DOM.
     * Подберите селектор, который точно соответствует ссылкам категорий в левом меню.
     */
    private List<String> parseCategoryLinksFromDom(Page page) {
        // Например, ссылки в левом меню Samokat можно найти по селектору "a.CategoryLink_root__FXcVU"
        List<Locator> catLinkLocators = page.locator("a.CategoryLink_root__FXcVU").all();

        List<String> urls = new ArrayList<>();
        for (Locator link : catLinkLocators) {
            String href = link.getAttribute("href");
            if (href != null && href.startsWith("/category")) {
                // Превратим в абсолютный URL
                String fullUrl = page.url().split("/#")[0]; // грубый пример
                if (href.startsWith("/")) {
                    fullUrl = "https://samokat.ru" + href;
                } else {
                    fullUrl = href;
                }
                urls.add(fullUrl);
            }
        }
        return urls;
    }

    /**
     * Пример парсинга товаров со страницы категории.
     * Вам нужно найти карточки товаров (например, по ".ProductCard_root__OCLMl")
     * и вытащить название, цены и т.п.
     */
    private List<SamokatProductDto> parseProductsFromDom(Page page) {
        List<SamokatProductDto> products = new ArrayList<>();

        List<Locator> productCards = page.locator(".ProductCard_root__OCLMl").all();
        for (Locator card : productCards) {
            // Название
            String name = card.locator(".ProductCard_name__2VDcL").innerText().trim();
            // Пример: priceBlock = "119 ₽"
            String priceBlock = card.locator(".ProductCardActions_text__3Uohy").innerText().trim();

            // Выделяем число из "119 ₽"
            Integer priceInKopecks = parsePriceToKopecks(priceBlock);

            SamokatProductDto dto = new SamokatProductDto();
            dto.setName(name);
            // Упростим: dto.setUuid(...) можете сгенерировать или не использовать
            // Зададим current price (pickup price можно пропустить)
            SamokatPrices pricesDto = new SamokatPrices();
            pricesDto.setCurrent(priceInKopecks);
            dto.setPrices(pricesDto);

            products.add(dto);
        }
        return products;
    }

    private Integer parsePriceToKopecks(String priceText) {
        // Допустим "119 ₽" -> 11900
        // Или "1 250 ₽" -> 125000
        String digitsOnly = priceText.replaceAll("[^0-9]", "");
        if (digitsOnly.isEmpty()) {
            return null;
        }
        int rubles = Integer.parseInt(digitsOnly);
        return rubles * 100;
    }

    /**
     * Сохраняем товары в БД. Логика взята из вашего parseAndSaveCategoryDetail,
     * но упрощена: мы не разбиваем на подкатегории.
     */
    private void saveProducts(List<SamokatProductDto> productDtos) {
        Delivery samokatDelivery = deliveryRepository.findByName("Samokat")
                .orElseGet(() -> {
                    Delivery d = new Delivery();
                    d.setName("Samokat");
                    d.setUrl("https://samokat.ru/");
                    return deliveryRepository.save(d);
                });

        for (SamokatProductDto dto : productDtos) {
            // Создаем Product
            Product product = new Product();
            product.setName(dto.getName());
            product.setDescription("Parsed from HTML Samokat"); // при желании

            List<ProductPrice> prices = new ArrayList<>();
            if (dto.getPrices() != null && dto.getPrices().getCurrent() != null) {
                ProductPrice priceCurrent = new ProductPrice();
                BigDecimal priceRub = convertKopecksToRubles(dto.getPrices().getCurrent());
                priceCurrent.setPrice(priceRub);
                priceCurrent.setService(samokatDelivery);
                priceCurrent.setProduct(product);
                prices.add(priceCurrent);
            }
            product.setPrices(prices);

            productRepository.save(product);
            log.info("Сохранён товар: {}", product.getName());
        }
    }

    private BigDecimal convertKopecksToRubles(Integer kopecks) {
        if (kopecks == null) return BigDecimal.ZERO;
        return BigDecimal.valueOf(kopecks).divide(BigDecimal.valueOf(100));
    }

    /**
     * Переход с ретраем + обработка капчи.
     */
    private void navigateWithRetry(Page page, String url, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                handleCaptchaIfPresent(page);

                if (page.locator("body").isVisible()) {
                    return;
                }
            } catch (Exception e) {
                log.warn("Попытка {}: Ошибка навигации: {}", attempt, e.getMessage());
                page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            }
        }
        throw new RuntimeException("Не удалось загрузить страницу после " + maxRetries + " попыток");
    }

    /**
     * Капча — та же логика, что и у вас.
     */
    private void handleCaptchaIfPresent(Page page) {
        try {
            Locator captchaLocator = page.locator("img[src*='captcha']");
            if (captchaLocator.count() > 0) {
                log.info("Обнаружена капча, решаем...");
                byte[] imageBytes = captchaLocator.first().screenshot();
                String solution = imageCaptchaSolver.solveCaptcha(imageBytes);
                page.fill("input[name='captcha']", solution);
                page.click("button[type='submit']");

                page.waitForSelector("img[src*='captcha']",
                        new Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED));
                log.info("Капча решена и исчезла.");
            }
        } catch (Exception e) {
            log.error("Ошибка обработки капчи: {}", e.getMessage());
        }
    }
}