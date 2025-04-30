package etu.ru.deliverycosts.service.impl;

import etu.ru.deliverycosts.model.entity.Delivery;
import etu.ru.deliverycosts.model.entity.Product;
import etu.ru.deliverycosts.model.entity.ProductPrice;
import etu.ru.deliverycosts.repository.ProductRepository;
import etu.ru.deliverycosts.service.DeliveryService;
import etu.ru.deliverycosts.service.ProductService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;

    /**
     * Обновляет или сохраняет продукт в одной транзакции.
     */
    @Override
    @Transactional
    public void updateOrSaveProduct(Delivery delivery, String originalName, BigDecimal price) {
        String normName = Product.normalize(originalName);

        productRepository.findByNormalizedName(normName)
                .ifPresentOrElse(prod -> {
                    // защитимся от null в description
                    String desc = Optional.ofNullable(prod.getDescription()).orElse("");

                    prod.getPrices().stream()
                            .filter(pp -> pp.getService().getId().equals(delivery.getId()))
                            .findFirst()
                            .ifPresentOrElse(pp -> {
                                if (pp.getPrice().compareTo(price) != 0) {
                                    BigDecimal old = pp.getPrice();
                                    pp.setPrice(price);
                                    log.info("[{}] Обновили {}: {} → {}", delivery.getName(), prod.getName(), old, price);
                                }
                            }, () -> {
                                if (!desc.contains(delivery.getName())) {
                                    prod.setDescription(desc + ", Parsed from " + delivery.getName());
                                }
                                prod.getPrices().add(new ProductPrice(null, prod, delivery, price));
                                log.info("[{}] Добавили цену {}: {}", delivery.getName(), prod.getName(), price);
                            });
                    // одно сохранение в конце
                    productRepository.save(prod);
                }, () -> {
                    Product np = new Product();
                    np.setName(originalName); // тут normalizedName проставится автоматически
                    np.setDescription("Parsed from " + delivery.getName());
                    np.getPrices().add(new ProductPrice(null, np, delivery, price));
                    productRepository.save(np);
                    log.info("[{}] Создали {}: {}", delivery.getName(), originalName, price);
                });
    }


}
