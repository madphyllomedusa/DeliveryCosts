package etu.ru.deliverycosts.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import etu.ru.deliverycosts.model.entity.Delivery;
import etu.ru.deliverycosts.model.entity.Product;
import etu.ru.deliverycosts.model.entity.ProductPrice;
import etu.ru.deliverycosts.repository.DeliveryRepository;
import etu.ru.deliverycosts.repository.ProductRepository;
import etu.ru.deliverycosts.util.samokat.SamokatCategory;
import etu.ru.deliverycosts.util.samokat.SamokatCategoryWithProductsDto;
import etu.ru.deliverycosts.util.samokat.SamokatProductDto;
import etu.ru.deliverycosts.util.samokat.SamokatSubcategoryDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class SamokatUpdateService {

    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final DeliveryRepository deliveryRepository;
    private final ImageCaptchaSolver imageCaptchaSolver;

    @Autowired
    public SamokatUpdateService(
            ObjectMapper objectMapper,
            ProductRepository productRepository,
            DeliveryRepository deliveryRepository,
            ImageCaptchaSolver imageCaptchaSolver
    ) {
        this.objectMapper = objectMapper;
        this.productRepository = productRepository;
        this.deliveryRepository = deliveryRepository;
        this.imageCaptchaSolver = imageCaptchaSolver;
    }

    /**
     * Метод, который автоматически раз в час:
     * 1) Открывает https://samokat.ru/
     * 2) Перехватывает JSON с /categories/list и /categories/{uuid}
     * 3) Парсит и сохраняет товары
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void updateSamokatData() {
        log.info("Запуск обновления данных Samokat...");

        // Сюда собираем JSON с /categories/list
        List<String> categoriesListJsonStorage = new ArrayList<>();
        // Сюда собираем JSON с /categories/{uuid}
        List<String> detailedCategoriesJsonStorage = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            // Перехват ответов
            page.onResponse(response -> {
                String url = response.url();
                try {
                    // Общий список категорий
                    if (url.contains("/categories/list")) {
                        String json = response.text();
                        log.info("Перехвачен список категорий: {}", json);
                        categoriesListJsonStorage.add(json);

                    // Детальная категория (UUID в url)
                    } else if (url.matches(".*/categories/[0-9a-f\\-]{36}.*")) {
                        String json = response.text();
                        log.info("Перехвачена детальная категория: {}", json);
                        detailedCategoriesJsonStorage.add(json);
                    }
                } catch (Exception e) {
                    log.error("Ошибка при обработке ответа [{}]: {}", url, e.getMessage());
                }
            });

            // Заходим на главную страницу
            log.info("Открываем страницу https://samokat.ru/...");
            page.navigate("https://samokat.ru/");
            page.waitForTimeout(5000);

            // Проверяем наличие капчи
            if (page.locator("img[src*='captcha_image.php']").count() > 0) {
                log.info("Обнаружена капча, начинаем решать...");
                // Скачиваем картинку капчи
                byte[] captchaBytes = page.locator("img[src*='captcha_image.php']").screenshot();
                // Отправляем в 2captcha
                String solvedCaptchaText = imageCaptchaSolver.solveCaptcha(captchaBytes);

                // Вводим ответ в поле
                page.fill("input[name='captcha']", solvedCaptchaText);
                page.click("button[type='submit']");
                page.waitForTimeout(5000);
            }

            // Даем время, чтобы все нужные запросы отработали
            page.waitForTimeout(5000);

            browser.close();
        } catch (Exception e) {
            log.error("Ошибка Playwright: {}", e.getMessage(), e);
        }

        // Проверка, что мы что-то реально собрали
        if (categoriesListJsonStorage.isEmpty() && detailedCategoriesJsonStorage.isEmpty()) {
            log.warn("Не удалось получить JSON (пусто).");
            return;
        }

        // Парсим и сохраняем
        try {
            // 1) /categories/list
            for (String catJson : categoriesListJsonStorage) {
                List<SamokatCategory> catList = objectMapper.readValue(catJson, new TypeReference<>() {});
                catList.forEach(c -> log.info("Категория: id={}, name={}", c.getId(), c.getName()));
            }

            // 2) /categories/{uuid}
            for (String detailJson : detailedCategoriesJsonStorage) {
                SamokatCategoryWithProductsDto detailCat =
                        objectMapper.readValue(detailJson, SamokatCategoryWithProductsDto.class);
                parseAndSaveCategoryDetail(detailCat);
            }

            log.info("Обновление Samokat завершено успешно.");
        } catch (Exception e) {
            log.error("Ошибка при парсинге JSON: {}", e.getMessage(), e);
        }
    }

    /**
     * Разбирает детальную категорию и сохраняет товары.
     */
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

    /**
     * Сохраняем один товар (product + его цены).
     */
    private void saveProduct(SamokatProductDto dto, Delivery samokatDelivery) {
        // Для простоты создаём новый Product на каждый заход,
        // но можно доработать логику, чтобы искать по uuid
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
