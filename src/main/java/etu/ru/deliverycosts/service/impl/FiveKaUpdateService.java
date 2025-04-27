package etu.ru.deliverycosts.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import etu.ru.deliverycosts.model.entity.Delivery;
import etu.ru.deliverycosts.model.entity.Product;
import etu.ru.deliverycosts.model.entity.ProductPrice;
import etu.ru.deliverycosts.repository.DeliveryRepository;
import etu.ru.deliverycosts.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
@RequiredArgsConstructor
public class FiveKaUpdateService {

    private static final String STORE_ID = "Y232";
    private static final String BASE_API =
            "https://5d.5ka.ru/api/catalog/v2/stores/%s/categories/%s/products?offset=0&limit=500";

    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final DeliveryRepository deliveryRepository;

    /**
     * Playwright page сохраняем, чтобы пользоваться waitForResponse() в других методах
     */
    private Page page;

    /**
     * ID всех найденных категорий.  CopyOnWrite — onResponse вызывается в отдельном потоке.
     */
    private final List<String> categories = new CopyOnWriteArrayList<>();

    public void updateFiveKaData() {
        log.info("[5KA] ▶️  Старт парсинга каталога …");
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            BrowserContext ctx = browser.newContext();

            ctx.setDefaultNavigationTimeout(30_000);
            ctx.setDefaultTimeout(30_000);

            page = ctx.newPage();

            // перехватываем все ответы — там лежат категории и товары
            ctx.onResponse(this::intercept);

                page.navigate("https://5ka.ru/catalog",
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            log.info("[5KA] Открыли каталог, ждём сетевые ответы с категориями …");
            page.waitForTimeout(15_000);

            Collections.shuffle(categories);
            for (String catId : categories) {
                log.info("[5KA] ➡️  Переходим в категорию {}", catId);
                fetchProducts(catId);
                page.waitForTimeout(10_000);
            }
            log.info("[5KA] ✅  Парсинг завершён, закрываем браузер.");
        } catch (Exception e) {
            log.error("[5KA] ❌ Общая ошибка обновления", e);
        }
    }

    /* ============================== intercept ============================== */
    private void intercept(Response resp) {
        final String url = resp.url();
        try {
            // ответ со списком категорий (без /products)
            if (url.contains("/categories") && !url.contains("/products") && resp.status() == 200) {
                log.info("[5KA][XHR] прислали блок категорий: {}", url);
                handleCategoryResponse(resp.body());
            }
            // ответ со списком товаров
            if (url.contains("/products") && resp.status() == 200) {
                log.info("[5KA][XHR] прислали блок товаров: {}", url);
                handleProductsResponse(resp.body());
            }
        } catch (Exception ex) {
            log.error("[5KA] ❌ Ошибка обработки ответа {}", url, ex);
        }
    }

    /* ============================== категории ============================== */
    private void handleCategoryResponse(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);

            // API иногда отдаёт массив блоков, иногда один объект
            Queue<JsonNode> q = new ArrayDeque<>();
            if (root.isArray()) {
                root.forEach(q::add);
            } else {
                q.add(root);
            }

            while (!q.isEmpty()) {
                JsonNode block = q.poll();
                JsonNode cats = block.get("categories");
                if (cats == null || !cats.isArray()) {
                    continue; // в блоке нет категорий – пропускаем
                }
                for (JsonNode c : cats) {
                    String id = c.path("id").asText(null);
                    String name = c.path("name").asText("");
                    if (id == null || id.isBlank()) {
                        continue;
                    }
                    if (!categories.contains(id)) {
                        categories.add(id);
                        log.info("[5KA] ➕ Найдена категория '{}' ({})", name, id);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[5KA] ❌ Ошибка разбора категорий", e);
        }
    }

    /* ============================== товары ============================== */
    private void fetchProducts(String categoryId) {
        // 1. находим ссылку плитки категории
        Locator link = page.locator(String.format("a[data-category-id='%s'], a[href*='%s']", categoryId, categoryId)).first();

        if (link.count() == 0) {
            log.warn("[5KA] ⚠️  Не нашли DOM-ссылку для categoryId={} — пропускаем", categoryId);
            return;
        }

        String apiPart = "/categories/" + categoryId + "/products";

        // 2. ждём XHR с товарами именно для этой категории
        Response resp = page.waitForResponse(r -> r.url().contains(apiPart) && r.status() == 200, () -> {
            link.click();           // триггер — клик в меню
            scrollUntilNoNewProducts(page);
        });

        if (resp == null) {
            log.warn("[5KA] ⚠️  Не дождались products-XHR для categoryId={}", categoryId);
            return;
        }

        log.info("[5KA] ⬇️  Получили список товаров для категории {} ({} байт)", categoryId, resp.body().length);
        handleProductsResponse(resp.body());

        // 3. возвращаемся обратно к списку категорий
        page.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
    }

    private void handleProductsResponse(byte[] body) {
        try {
            JsonNode productsNode = objectMapper.readTree(body).get("products");
            if (productsNode != null && productsNode.isArray()) {
                saveProducts(productsNode);
            } else {
                log.info("[5KA] Пустой или неверный массив products ({} байт)", body.length);
            }
        } catch (Exception e) {
            log.error("[5KA] ❌ Ошибка обработки продуктов", e);
        }
    }

    /* ============================== сохранение в БД ============================== */
    private void saveProducts(JsonNode products) {
        Delivery delivery = deliveryRepository.findByName("5ka").orElseGet(() -> {
            Delivery d = new Delivery();
            d.setName("5ka");
            d.setUrl("https://5ka.ru");
            return deliveryRepository.save(d);
        });

        for (JsonNode p : products) {
            String name = p.path("name").asText();
            JsonNode pricesNode = p.path("prices");

            String priceStr = pricesNode.path("discount").isNull() ? pricesNode.path("regular").asText() : pricesNode.path("discount").asText();
            if (priceStr == null || priceStr.isBlank()) {
                log.info("[5KA] '{}' – нет цены, пропускаем", name);
                continue;
            }
            BigDecimal price = new BigDecimal(priceStr);

            productRepository.findByName(name).ifPresentOrElse(prod -> {
                // обновляем цену
                prod.getPrices().stream()
                        .filter(pp -> pp.getService().getId().equals(delivery.getId()))
                        .findFirst()
                        .ifPresentOrElse(pp -> {
                            if (pp.getPrice().compareTo(price) != 0) {
                                log.info("[5KA] ⬆️  Обновляем цену '{}' с {} на {}", name, pp.getPrice(), price);
                                pp.setPrice(price);
                            }
                        }, () -> {
                            log.info("[5KA] ➕ Добавляем новую цену для существующего товара '{}' = {}", name, price);
                            prod.getPrices().add(new ProductPrice(null, prod, delivery, price));
                        });
                productRepository.save(prod);
            }, () -> {
                // новый товар
                log.info("[5KA] 🆕 Создаём новый товар '{}' = {}", name, price);
                Product newProd = new Product();
                newProd.setName(name);
                newProd.setDescription("Parsed from 5ka");
                newProd.getPrices().add(new ProductPrice(null, newProd, delivery, price));
                productRepository.save(newProd);
            });
        }
    }

        private void scrollUntilNoNewProducts(Page page) {
        int sameCountTimes = 0;
        while (sameCountTimes < 3) {
            int currentCount = page.locator(".productFilterGrid_cardContainer__oyUJZ").count();
            page.evaluate("window.scrollBy(0, 3000)");
            page.waitForTimeout(2000);
            int newCount = page.locator(".productFilterGrid_cardContainer__oyUJZ").count();
            if (newCount <= currentCount) {
                sameCountTimes++;
            } else {
                sameCountTimes = 0;
            }
        }
    }
}