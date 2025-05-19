package etu.ru.deliverycosts.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import etu.ru.deliverycosts.model.entity.Delivery;
import etu.ru.deliverycosts.repository.DeliveryRepository;
import etu.ru.deliverycosts.repository.ProductRepository;
import etu.ru.deliverycosts.service.ProductService;
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

    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final ProductService productService;
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
        log.info("[Пятерочка] ▶️  Старт парсинга каталога …");
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
            log.info("[Пятерочка] Открыли каталог, ждём сетевые ответы с категориями …");
            page.waitForTimeout(15_000);

            Collections.shuffle(categories);
            for (String catId : categories) {
                log.info("[Пятерочка] ➡️  Переходим в категорию {}", catId);
                fetchProducts(catId);
                page.waitForTimeout(10_000);
            }
            log.info("[Пятерочка] ✅  Парсинг завершён, закрываем браузер.");
        } catch (Exception e) {
            log.error("[Пятерочка] ❌ Общая ошибка обновления", e);
        }
    }

    /* ============================== intercept ============================== */
    private void intercept(Response resp) {
        final String url = resp.url();
        try {
            // ответ со списком категорий (без /products)
            if (url.contains("/categories") && !url.contains("/products") && resp.status() == 200) {
                log.info("[Пятерочка][XHR] прислали блок категорий: {}", url);
                handleCategoryResponse(resp.body());
            }
            // ответ со списком товаров
            if (url.contains("/products") && resp.status() == 200) {
                log.info("[Пятерочка][XHR] прислали блок товаров: {}", url);
                handleProductsResponse(resp.body());
            }
        } catch (Exception ex) {
            log.error("[Пятерочка] ❌ Ошибка обработки ответа {}", url, ex);
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
                        log.info("[Пятерочка] ➕ Найдена категория '{}' ({})", name, id);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[Пятерочка] ❌ Ошибка разбора категорий", e);
        }
    }

    /* ============================== товары ============================== */
    private void fetchProducts(String categoryId) {
        String apiPart = "/categories/" + categoryId + "/products";

        Response resp = page.waitForResponse(r -> r.url().contains(apiPart) && r.status() == 200, () -> {
            page.navigate("https://5ka.ru/catalog/" + categoryId, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            scrollUntilNoNewProducts(page);
        });

        if (resp == null) {
            log.warn("[Пятерочка] ⚠️  Не дождались products-XHR для categoryId={}", categoryId);
            return;
        }

        log.info("[Пятерочка] ⬇️  Получили список товаров для категории {} ({} байт)", categoryId, resp.body().length);
        handleProductsResponse(resp.body());

        page.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
    }


    private void handleProductsResponse(byte[] body) {
        try {
            JsonNode productsNode = objectMapper.readTree(body).get("products");
            if (productsNode != null && productsNode.isArray()) {
                saveProducts(productsNode);
            } else {
                log.info("[Пятерочка] Пустой или неверный массив products ({} байт)", body.length);
            }
        } catch (Exception e) {
            log.error("[Пятерочка] ❌ Ошибка обработки продуктов", e);
        }
    }

    /* ============================== сохранение в БД ============================== */
    private void saveProducts(JsonNode products) {
        Delivery delivery = deliveryRepository.findByName("Пятерочка").orElseGet(() -> {
            Delivery d = new Delivery();
            d.setName("Пятерочка");
            d.setUrl("https://5ka.ru");
            return deliveryRepository.save(d);
        });

        for (JsonNode p : products) {
            String name = p.path("name").asText();
            JsonNode pricesNode = p.path("prices");

            String priceStr = pricesNode.path("discount").isNull()
                    ? pricesNode.path("regular").asText()
                    : pricesNode.path("discount").asText();

            if (priceStr == null || priceStr.isBlank()) {
                log.info("[Пятерочка] '{}' – нет цены, пропускаем", name);
                continue;
            }
            BigDecimal price = new BigDecimal(priceStr);

            productService.updateOrSaveProduct(delivery, name, price);

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