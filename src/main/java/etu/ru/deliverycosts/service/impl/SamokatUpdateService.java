package etu.ru.deliverycosts.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

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
                            .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
                            .setJavaScriptEnabled(true)
                            .setBypassCSP(true)
                            .setViewportSize(1200, 800)
            );

            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "*/*");
            headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
            headers.put("Accept-Language", "ru-RU,ru;q=0.9");
            headers.put("Connection", "keep-alive");
            headers.put("Origin", "https://samokat.ru");
            headers.put("Referer", "https://samokat.ru/");
            headers.put("Sec-Fetch-Dest", "empty");
            headers.put("Sec-Fetch-Mode", "cors");
            headers.put("Sec-Fetch-Site", "same-site");
            headers.put("X-Requested-With", "XMLHttpRequest");
            context.setExtraHTTPHeaders(headers);

            context.addInitScript(
                    "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });" +
                            "Object.defineProperty(navigator, 'plugins', { get: () => [1,2,3,4,5] });" +
                            "Object.defineProperty(navigator, 'languages', { get: () => ['ru-RU', 'ru'] });" +
                            "window.navigator.chrome = { runtime: {} };"
            );

            String uniqueSpid = UUID.randomUUID().toString() + "_" + System.currentTimeMillis();

            Cookie cookie = new Cookie("spid", uniqueSpid);
            cookie.setDomain(".samokat.ru");
            cookie.setPath("/");
            cookie.setSecure(true);
            cookie.setHttpOnly(false);
            //cookie.setSameSite("Lax"); // при необходимости

            context.addCookies(List.of(cookie));


            Page page = context.newPage();
            // Увеличим общий таймаут на 120 секунд
            page.setDefaultTimeout(120_000);

            // 1) Переходим на главную
            navigateWithRetry(page, "https://samokat.ru/", 3);
            page.waitForTimeout(5000);

            // 2) Собираем все основные категории из бокового меню
            List<String> mainCategories = parseMainCategories(page);
            log.info("Найдено {} основных категорий в боковом меню", mainCategories.size());

            // 3) Рекурсивно обходим каждую найденную категорию
            Collections.shuffle(mainCategories);
            for (String catUrl : mainCategories) {
                parseCategory(page, catUrl, 0);
            }


            browser.close();
        } catch (Exception e) {
            log.error("Критическая ошибка: {}", e.getMessage(), e);
        }
    }

    /**
     * Парсит все «верхние» категории из бокового меню, чтобы затем по ним пройтись рекурсивно.
     */
    private List<String> parseMainCategories(Page page) {
        List<String> result = new ArrayList<>();

        // Селектор для блока бокового меню - подберите точный, исходя из реального DOM
        Locator sidebarMenu = page.locator("div[data-fsd='widget/CatalogTree']");
        if (sidebarMenu.count() == 0) {
            log.warn("Не нашли боковое меню, возможно селектор изменился?");
            return result;
        }

        // Селектор для ссылок в боковом меню
        Locator links = sidebarMenu.locator("a.CategoryLink_root__FXcVU.CatalogTreeSectionCard_category__CzzhA");
        int count = links.count();
        for (int i = 0; i < count; i++) {
            String href = links.nth(i).getAttribute("href");
            if (href != null && href.startsWith("/category")) {
                // Превращаем относительную ссылку в абсолютную
                String fullUrl = "https://samokat.ru" + href;
                result.add(fullUrl);
            }
        }
        return result;
    }

    /**
     * Рекурсивный метод: идём на страницу категории, смотрим «теги подкатегорий» (CategoryTagsList).
     * Если они есть — для каждой ссылки вызываем parseCategory(...).
     * Если нет подкатегорий — парсим товары.
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
            // Нет подкатегорий, значит это финальный список товаров
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
            emulateHumanBehavior(page);
            saveProducts(products);

        } else {
            // Есть подкатегории
            log.info("{}   Найдено подкатегорий (CategoryTagsList): {}", indent(depth), subcats.size());

            for (String subUrl : subcats) {
                parseCategory(page, subUrl, depth + 1);

                // Возврат обратно или заново переходим на исходный categoryUrl
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

        Locator tagsBlock = page.locator("div.CategoryTagsList_root__uCIrg");
        if (tagsBlock.count() == 0) {
            return result; // нет «тегов» на странице
        }

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
 * Парсим товары. Логика обновлена: отдельно парсим цену со скидкой и без скидки.
 */
private List<SamokatProductDto> parseProductsFromDom(Page page) {
    List<SamokatProductDto> products = new ArrayList<>();
    List<Locator> productCards = page.locator(".ProductCard_root__OCLMl").all();

    for (Locator card : productCards) {
        // Название
        String name = card.locator(".ProductCard_name__2VDcL").innerText().trim();

        // Используем обновлённый метод парсинга цены
        Integer priceKopecks = parsePriceToKopecks(card);

        SamokatProductDto dto = new SamokatProductDto();
        dto.setName(name);

        SamokatPrices pricesDto = new SamokatPrices();
        pricesDto.setCurrent(priceKopecks);
        dto.setPrices(pricesDto);

        products.add(dto);
    }
    return products;
}

/**
 * Забираем из карточки только «актуальную» цену:
 * Samokat в одном и том же контейнере рисует сначала старую,
 * потом новую цену. Берём последнюю <span>.
 */
private Integer parsePriceToKopecks(Locator card) {
    // в блоке .ProductCardActions_text__3Uohy лежит несколько <span>
    Locator spans = card.locator(".ProductCardActions_text__3Uohy span");
    int count = spans.count();
    if (count == 0) {
        return null;  // цены нет
    }

    // последний <span> — это действующая цена
    String priceText = spans.nth(count - 1).innerText().trim();

    // переводим «231 ₽» → 23100
    priceText = priceText.replaceAll("[^0-9]", "");
    if (priceText.isEmpty()) {
        return null;
    }
    return Integer.parseInt(priceText) * 100;
}


/**
 * Преобразование цены из текста в копейки.
 */
private Integer convertPriceTextToKopecks(String priceText) {
    if (priceText == null) return null;
    priceText = priceText.replaceAll("[^0-9]", "");
    if (priceText.isEmpty()) return null;
    return Integer.parseInt(priceText) * 100;
}

    /**
     * Сохранение списка товаров в БД, привязка к доставке "Samokat".
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

    /**
     * Скроллим вниз, пока количество карточек товаров растёт,
     * либо прерываемся, если 3 раза подряд не растёт.
     */
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

    /**
     * Повторная навигация в случае неудачи (капчи и пр.).
     */
    private void navigateWithRetry(Page page, String url, int maxRetries) {
        for (int i = 1; i <= maxRetries; i++) {
            try {
                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                handleCaptchaIfPresent(page);

                refreshBrokenPage(page);          // ← новый вызов

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

    /**
     * Обработка возможной капчи.
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
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.DETACHED)
                                .setTimeout(120_000));

                log.info("Капча решена и исчезла.");
            }
        } catch (Exception e) {
            log.error("Ошибка обработки капчи: {}", e.getMessage());
        }
    }

    private void emulateHumanBehavior(Page page) {
        Random random = new Random();

        // Плавный скроллинг
        for (int i = 0; i < 3; i++) {
            page.mouse().wheel(0, random.nextInt(800) + 200);
            page.waitForTimeout(1000 + random.nextInt(2000));
        }

        // Случайные перемещения курсора
        for (int i = 0; i < 5; i++) {
            page.mouse().move(random.nextInt(1000), random.nextInt(800));
            page.waitForTimeout(500 + random.nextInt(1000));
        }

        // Случайное открытие товаров
        List<Locator> products = page.locator(".ProductCard_root__OCLMl").all();
        if (!products.isEmpty()) {
            Locator randomProduct = products.get(random.nextInt(products.size()));
            randomProduct.click();
            page.waitForTimeout(3000 + random.nextInt(3000));

            // Иногда добавляем товар в корзину
            if (random.nextBoolean()) {
                Locator addButton = page.locator("button[data-fsd='cart-item-btn']");
                if (addButton.isVisible()) {
                    addButton.click();
                    page.waitForTimeout(2000 + random.nextInt(2000));
                    // потом убираем товар обратно (опционально)
                    Locator removeButton = page.locator("button[data-fsd='cart-item-btn-remove']");
                    if (removeButton.isVisible()) {
                        removeButton.click();
                        page.waitForTimeout(2000 + random.nextInt(2000));
                    }
                }
            }

            // возвращаемся назад
            page.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
        }
    }

    private void refreshBrokenPage(Page page) {
    // Проверяем заголовок ошибки или саму кнопку
    Locator errorHeader = page.locator("text=Простите, мы сломались");
    Locator refreshBtn  = page.locator("button:has-text(\"Обновить\")");

    if (errorHeader.count() > 0 || refreshBtn.count() > 0) {
        // пауза 1‑2 сек
        page.waitForTimeout(1000 + new Random().nextInt(1000));

        if (refreshBtn.count() > 0 && refreshBtn.first().isVisible()) {
            refreshBtn.first().click();
            // ждём полной загрузки
            page.waitForLoadState(LoadState.NETWORKIDLE);
            log.info("Страница перезагружена через кнопку «Обновить».");
        }
    }
}


    private String indent(int depth) {
        return "  ".repeat(Math.max(0, depth));
    }
}
