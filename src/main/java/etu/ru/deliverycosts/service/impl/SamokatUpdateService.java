package etu.ru.deliverycosts.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SamokatUpdateService {

    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final DeliveryRepository deliveryRepository;
    private final ImageCaptchaSolver imageCaptchaSolver;

    // Чтобы не парсить один и тот же URL бесконечно
    private final Set<String> visitedUrls = new HashSet<>();

    @Scheduled(cron = "0 0 * * * ?")
    public void updateSamokatData() {
        log.info("Начало обновления данных Samokat...");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--disable-web-security",
                        "--disable-dev-shm-usage"
                    ))
            );

            BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setExtraHTTPHeaders(Map.of(
                        "Accept", "application/json",
                        "Accept-Language", "ru-RU,ru;q=0.9",
                        "Origin", "https://samokat.ru",
                        "Referer", "https://samokat.ru/"
                    ))
                    .setViewportSize(1200, 800)
            );

            // Скрываем navigator.webdriver
            context.addInitScript(
                "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });" +
                "window.chrome = { runtime: {} };"
            );

            Page page = context.newPage();
            // Увеличим общий таймаут на 120 секунд
            page.setDefaultTimeout(120_000);

            // 1) Переходим на главную
            navigateWithRetry(page, "https://samokat.ru/", 3);

            // Небольшая пауза
            page.waitForTimeout(5000);

            // Предположим, что «верхний уровень» категорий мы тоже можем взять из левого меню,
            // или просто «что на завтрак» — как вам нужно
            // Для примера возьмём: /category/chto-na-zavtrak
            String topCategory = "https://samokat.ru/category/chto-na-zavtrak";
            parseCategory(page, topCategory, 0);

            browser.close();
        } catch (Exception e) {
            log.error("Критическая ошибка: {}", e.getMessage(), e);
        }
    }

    /**
     * Рекурсивный метод: идём на страницу, смотрим «теги подкатегорий» (CategoryTagsList),
     * если они есть — для каждой ссылки вызываем parseCategory.
     * Если нет подкатегорий (или теги не найдены) — парсим товары.
     */
    private void parseCategory(Page page, String categoryUrl, int depth) {
        // Проверка, посещали ли уже
        if (visitedUrls.contains(categoryUrl)) {
            log.warn("{}Уже посещали URL, пропускаем: {}", indent(depth), categoryUrl);
            return;
        }
        visitedUrls.add(categoryUrl);

        log.info("{}=> Открываем категорию: {}", indent(depth), categoryUrl);

        page.navigate(categoryUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
        handleCaptchaIfPresent(page);

        // Небольшая пауза на рендер
        page.waitForTimeout(2000);

        // Пробуем найти подкатегории (теги) в блоке CategoryTagsList
        List<String> subcats = parseCategoryTags(page);

        if (subcats.isEmpty()) {
            // Нет подкатегорий в блоке, значит это финальный список товаров
            log.info("{}   Нет подкатегорий, пытаемся спарсить товары", indent(depth));
            scrollUntilNoNewProducts(page);

            // Ждём появления карточек
            try {
                page.waitForSelector(".ProductCard_root__OCLMl",
                        new Page.WaitForSelectorOptions().setTimeout(120_000));
            } catch (PlaywrightException ex) {
                log.warn("{}   Похоже, не дождались товаров: {}", indent(depth), ex.getMessage());
            }

            // Парсим товары
            List<SamokatProductDto> products = parseProductsFromDom(page);
            log.info("{}   Товаров на странице: {}", indent(depth), products.size());
            saveProducts(products);

        } else {
            // Есть подкатегории
            log.info("{}   Найдено подкатегорий (CategoryTagsList): {}", indent(depth), subcats.size());

            for (String subUrl : subcats) {
                parseCategory(page, subUrl, depth + 1);

                // Возврат обратно (или заново переходить на categoryUrl)
                page.navigate(categoryUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                handleCaptchaIfPresent(page);
            }
        }
    }

    /**
     * Ищем только те подкатегории, которые лежат в блоке data-fsd="feature/CategoryTagsList".
     * Внутри него — ссылки a.CategoryLink_root__FXcVU.CategoryTagsList_link__PhUE2
     */
    private List<String> parseCategoryTags(Page page) {
        List<String> result = new ArrayList<>();

        // Селектор для блока с «тегами» под категорией
        Locator tagsBlock = page.locator("div.CategoryTagsList_root__uCIrg");
        if (tagsBlock.count() == 0) {
            return result; // нет «тегов» на странице
        }

        // Селектор для ссылок внутри этого блока
        Locator links = tagsBlock.locator("a.CategoryLink_root__FXcVU.CategoryTagsList_link__PhUE2");
        int count = links.count();
        for (int i = 0; i < count; i++) {
            String href = links.nth(i).getAttribute("href");
            if (href != null && href.startsWith("/category")) {
                String fullUrl = "https://samokat.ru" + href;
                result.add(fullUrl);
            }
        }
        return result;
    }

    /**
     * Парсим товары. Логика такая же, как была.
     */
    private List<SamokatProductDto> parseProductsFromDom(Page page) {
        List<SamokatProductDto> products = new ArrayList<>();
        List<Locator> productCards = page.locator(".ProductCard_root__OCLMl").all();

        for (Locator card : productCards) {
            String name = card.locator(".ProductCard_name__2VDcL").innerText().trim();
            Locator priceLocator = card.locator(".ProductCardActions_text__3Uohy");
            String priceText = (priceLocator.count() > 0) ? priceLocator.innerText().trim() : null;

            Integer priceKopecks = parsePriceToKopecks(priceText);

            SamokatProductDto dto = new SamokatProductDto();
            dto.setName(name);
            SamokatPrices pricesDto = new SamokatPrices();
            pricesDto.setCurrent(priceKopecks);
            dto.setPrices(pricesDto);

            products.add(dto);
        }
        return products;
    }

    private Integer parsePriceToKopecks(String priceText) {
        if (priceText == null) return null;
        String digitsOnly = priceText.replaceAll("[^0-9]", "");
        if (digitsOnly.isEmpty()) {
            return null;
        }
        int rubles = Integer.parseInt(digitsOnly);
        return rubles * 100;
    }

    private void saveProducts(List<SamokatProductDto> productDtos) {
        Delivery samokatDelivery = deliveryRepository.findByName("Samokat")
                .orElseGet(() -> {
                    Delivery d = new Delivery();
                    d.setName("Samokat");
                    d.setUrl("https://samokat.ru/");
                    return deliveryRepository.save(d);
                });

        for (SamokatProductDto dto : productDtos) {
            String name = dto.getName();
            if (dto.getPrices() == null || dto.getPrices().getCurrent() == null) {
                continue;
            }
            BigDecimal newPrice = convertKopecksToRubles(dto.getPrices().getCurrent());

            // Ищем товар по имени
            Optional<Product> existingOpt = productRepository.findByName(name);
            if (existingOpt.isPresent()) {
                Product existing = existingOpt.get();

                // Ищем цену Samokat
                Optional<ProductPrice> maybePrice = existing.getPrices().stream()
                        .filter(p -> p.getService().getId().equals(samokatDelivery.getId()))
                        .findFirst();

                if (maybePrice.isPresent()) {
                    ProductPrice oldPrice = maybePrice.get();
                    if (oldPrice.getPrice().compareTo(newPrice) != 0) {
                        log.info("Обновляем цену '{}' с {} на {}", name, oldPrice.getPrice(), newPrice);
                        oldPrice.setPrice(newPrice);
                        productRepository.save(existing);
                    }
                } else {
                    ProductPrice pp = new ProductPrice();
                    pp.setPrice(newPrice);
                    pp.setService(samokatDelivery);
                    pp.setProduct(existing);

                    existing.getPrices().add(pp);
                    productRepository.save(existing);
                    log.info("Добавили цену Samokat для '{}': {}", name, newPrice);
                }
            } else {
                // Создаём новый товар
                Product newProd = new Product();
                newProd.setName(name);
                newProd.setDescription("Parsed from CategoryTagsList");

                ProductPrice pp = new ProductPrice();
                pp.setPrice(newPrice);
                pp.setService(samokatDelivery);
                pp.setProduct(newProd);

                List<ProductPrice> prices = new ArrayList<>();
                prices.add(pp);
                newProd.setPrices(prices);

                productRepository.save(newProd);
                log.info("Создали новый товар '{}' = {}", name, newPrice);
            }
        }
    }

    private BigDecimal convertKopecksToRubles(Integer kopecks) {
        if (kopecks == null) return BigDecimal.ZERO;
        return BigDecimal.valueOf(kopecks).divide(BigDecimal.valueOf(100));
    }

    // Скроллим вниз, пока количество товаров растёт, либо 3 раза подряд не растёт
    private void scrollUntilNoNewProducts(Page page) {
        int sameCountTimes = 0;
        while (sameCountTimes < 3) {
            int currentCount = page.locator(".ProductCard_root__OCLMl").count();
            page.evaluate("window.scrollBy(0, 3000)");
            page.waitForTimeout(2000);
            int newCount = page.locator(".ProductCard_root__OCLMl").count();
            if (newCount <= currentCount) {
                sameCountTimes++;
            } else {
                sameCountTimes = 0;
            }
        }
    }

    private void navigateWithRetry(Page page, String url, int maxRetries) {
        for (int i = 1; i <= maxRetries; i++) {
            try {
                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                handleCaptchaIfPresent(page);

                if (page.locator("body").isVisible()) {
                    return;
                }
            } catch (Exception e) {
                log.warn("Попытка {}: Ошибка навигации: {}", i, e.getMessage());
                page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            }
        }
        throw new RuntimeException("Не удалось загрузить страницу после " + maxRetries + " попыток");
    }

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
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.DETACHED)
                                .setTimeout(120_000));

                log.info("Капча решена и исчезла.");
            }
        } catch (Exception e) {
            log.error("Ошибка обработки капчи: {}", e.getMessage());
        }
    }

    private String indent(int depth) {
        return "  ".repeat(Math.max(0, depth));
    }
}
