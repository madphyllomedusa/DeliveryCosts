package etu.ru.deliverycosts.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import etu.ru.deliverycosts.model.entity.Delivery;
import etu.ru.deliverycosts.repository.DeliveryRepository;
import etu.ru.deliverycosts.repository.ProductRepository;
import etu.ru.deliverycosts.service.ProductService;
import etu.ru.deliverycosts.util.samokat.SamokatCategoryInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Service
@RequiredArgsConstructor
public class SamokatUpdateService {

    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final ProductService productService;
    private final DeliveryRepository deliveryRepository;

    private static final String BASE_API = "https://api-web.samokat.ru/v2/showcases/";
    private Page page;
    private final Set<SamokatCategoryInfo> categories = new CopyOnWriteArraySet<>();
    private volatile boolean mainParsed = false;
    private final Random rnd = new Random();

    //@Scheduled(cron = "0 0 * * * ?")
    public void updateSamokatData() {
        log.info("[Самокат] ▶️  Начало обновления Самокат...");
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium()
                .launch(new BrowserType.LaunchOptions().setHeadless(false));
            BrowserContext ctx = browser.newContext();
            ctx.setDefaultNavigationTimeout(120_000);
            ctx.setDefaultTimeout(120_000);

            page = ctx.newPage();
            ctx.onResponse(this::interceptMain);

            // Инициализация UI
            page.navigate("https://samokat.ru/", new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForLoadState(LoadState.NETWORKIDLE);

            int waitCount = 0;
            while (!mainParsed && waitCount < 20) {
                page.waitForTimeout(1000);
                waitCount++;
            }

            if (!mainParsed) {
                log.warn("[Самокат] MAIN XHR не перехвачен за {} секунд, продолжаем с теми категориями, что есть", waitCount);
            }

            List<SamokatCategoryInfo> toProcess = new ArrayList<>(categories);
            Collections.shuffle(toProcess);
            for (SamokatCategoryInfo cat : toProcess) {
                processCategory(cat);
            }

            browser.close();
            log.info("[Самокат] ✅  Обновление завершено");
        } catch (Exception e) {
            log.error("[Самокат] ❌ Ошибка обновления", e);
        }
    }

    private void processCategory(SamokatCategoryInfo cat) {
        log.info("[Самокат] ➡️ Обрабатываем {} (slug={})", cat.getId(), cat.getSlug());
        try {
            Response prodResp = page.waitForResponse(
                r -> r.url().contains(BASE_API) && r.url().contains(cat.getId()) && r.status() == 200,
                () -> {
                    page.navigate("https://samokat.ru/category/" + cat.getSlug(), new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    handleBrokenPage(page);
                    emulateHumanBehavior(page);
                    scrollUntilNoNewProducts(page);
                }
            );
            log.info("[Самокат] PRODUCTS XHR: {}", prodResp.url());

            handleProductsResponse(prodResp.body());

            page.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(5000 + rnd.nextInt(5000));
        } catch (PlaywrightException e) {
            log.warn("[Самокат] Категория {} сломалась, пропускаем: {}", cat.getSlug(), e.getMessage());
        }
    }

    private void interceptMain(Response resp) {
        if (mainParsed || resp.status() != 200 || !(resp.url().contains(BASE_API) && resp.url().contains("/main"))) {
            return;
        }
        try {
            mainParsed = true;
            log.info("[Самокат] [XHR] MAIN перехвачен: {}", resp.url());
            List<SamokatCategoryInfo> parsed = parseMainCategories(resp.body());
            categories.addAll(parsed);
        } catch (Exception ex) {
            log.error("[Самокат] Ошибка парсинга MAIN", ex);
        }
    }

    private List<SamokatCategoryInfo> parseMainCategories(byte[] body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode cats = root.path("categories");
        List<SamokatCategoryInfo> out = new ArrayList<>();
        if (!cats.isArray() || cats.size() <= 2) {
            return out;
        }
        for (int i = 2; i < cats.size(); i++) {
            for (JsonNode sub : cats.get(i).path("categories")) {
                String uuid = sub.path("uuid").asText(null);
                String slug = sub.path("slug").asText(null);
                if (uuid != null && slug != null) {
                    out.add(new SamokatCategoryInfo(uuid, slug));
                    log.info("[Самокат] ➕ подкатегория {} (slug={})", uuid, slug);
                }
            }
        }
        return out;
    }

    private void handleProductsResponse(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode categoriesNode = root.path("categories");
            if (categoriesNode.isArray()) {
                for (JsonNode cat : categoriesNode) {
                    JsonNode prods = cat.path("products");
                    if (prods.isArray()) {
                        for (JsonNode p : prods) upsertSingle(p);
                    }
                    JsonNode preview = cat.path("preview").path("items");
                    if (preview.isArray()) {
                        for (JsonNode item : preview) upsertSingle(item.path("product"));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[Самокат] Ошибка обработки продуктов", e);
        }
    }

    private void upsertSingle(JsonNode p) {
        String name = p.path("name").asText(null);
        JsonNode prices = p.path("prices");
        if (name == null || prices.isMissingNode()) return;
        int current = prices.path("current").asInt(-1);
        if (current < 0) return;
        BigDecimal price = BigDecimal.valueOf(current, 2);
        upsertProduct(name, price);
    }

    private void upsertProduct(String name, BigDecimal price) {
        Delivery del = deliveryRepository.findByName("Самокат").orElseGet(() -> {
            Delivery d = new Delivery(); d.setName("Самокат"); d.setUrl("https://samokat.ru/");
            return deliveryRepository.save(d);
        });

        productService.updateOrSaveProduct(del, name, price);
    }

    private void scrollUntilNoNewProducts(Page page) {
        int same = 0;
        while (same < 3) {
            int before = page.locator(".ProductCard_root__OCLMl").count();
            page.evaluate("window.scrollBy(0,3000)");
            page.waitForTimeout(2000 + rnd.nextInt(1000));
            int after = page.locator(".ProductCard_root__OCLMl").count();
            same = (after <= before) ? same + 1 : 0;
        }
    }

    private void emulateHumanBehavior(Page page) {
        for (int i = 0; i < 3; i++) {
            page.mouse().wheel(0, rnd.nextInt(800) + 200);
            page.waitForTimeout(1000 + rnd.nextInt(2000));
        }
        for (int i = 0; i < 5; i++) {
            page.mouse().move(rnd.nextInt(1000), rnd.nextInt(800));
            page.waitForTimeout(500 + rnd.nextInt(1000));
        }
    }

    private void handleBrokenPage(Page page) {
        Locator errorContainer = page.locator("div[class*='ErrorScreen_container']");
        Locator refreshBtn = page.locator(".Button_control__V__YD");
        if (errorContainer.count() > 0 && errorContainer.first().isVisible()) {
            log.warn("[Самокат] Обнаружен экран ошибки, пробуем обновить страницу");
            if (refreshBtn.count() > 0 && refreshBtn.first().isVisible()) {
                refreshBtn.first().click();
                log.info("[Самокат] Нажали кнопку 'Обновить'");
            } else {
                page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                log.info("[Самокат] Перезагрузили страницу");
            }
            try {
                page.waitForSelector("div[class*='ErrorScreen_container']", new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.DETACHED)
                    .setTimeout(60_000)
                );
            } catch (PlaywrightException ex) {
                log.warn("[Самокат] Экран ошибки не исчез после ожидания: {}", ex.getMessage());
            }
            emulateHumanBehavior(page);
        }
    }
}
