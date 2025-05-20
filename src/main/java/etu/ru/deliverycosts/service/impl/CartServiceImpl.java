package etu.ru.deliverycosts.service.impl;

import etu.ru.deliverycosts.model.dto.response.CartByServiceResponse;
import etu.ru.deliverycosts.model.dto.response.CartResponse;
import etu.ru.deliverycosts.model.dto.response.ProductPriceResponse;
import etu.ru.deliverycosts.model.dto.response.ProductResponse;
import etu.ru.deliverycosts.model.entity.Delivery;
import etu.ru.deliverycosts.model.entity.Product;
import etu.ru.deliverycosts.model.entity.ProductPrice;
import etu.ru.deliverycosts.repository.DeliveryRepository;
import etu.ru.deliverycosts.repository.ProductPriceRepository;
import etu.ru.deliverycosts.repository.ProductRepository;
import etu.ru.deliverycosts.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Service;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Scope(value = WebApplicationContext.SCOPE_SESSION, proxyMode = ScopedProxyMode.TARGET_CLASS)
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {
    private final ProductRepository productRepository;
    private final DeliveryRepository deliveryRepository;
    private final ProductPriceRepository productPriceRepository;

    private final List<Long> cart = new ArrayList<>();

    @Override
    public void addProduct(Long productId) {
        cart.add(productId);
    }

    @Override
    public void clearCart() {
        cart.clear();
    }

    @Override
    public CartResponse getCart() {
        List<Delivery> services = deliveryRepository.findAll();
        List<Product> products = productRepository.findAllById(cart);

        List<ProductResponse> productResponses = products.stream().map(p -> {
            List<ProductPriceResponse> priceResponses = services.stream()
                    .map(svc -> {
                        Optional<BigDecimal> priceOpt = productPriceRepository
                                .findByProductIdAndServiceId(p.getId(), svc.getId())
                                .map(ProductPrice::getPrice);
                        return new ProductPriceResponse(
                                svc.getName(),
                                priceOpt.orElse(null)
                        );
                    })
                    .toList();

            var resp = new ProductResponse();
            resp.setName(p.getName());
            resp.setPriceByService(priceResponses);
            return resp;
        }).toList();

        // 3) Группируем и суммируем итоговые суммы по каждому сервису,
        //    а также считаем количество доступных позиций:
        Map<String, BigDecimal> sums = new HashMap<>();
        Map<String, Long> counts = new HashMap<>();

        for (var pr : productResponses) {
            for (var pp : pr.getPriceByService()) {
                String svc = pp.getServiceName();
                BigDecimal price = pp.getPrice();
                // Суммируем только существующие цены
                sums.merge(svc, price != null ? price : BigDecimal.ZERO, BigDecimal::add);
                // Считаем, в скольких товарах сервис присутствует
                if (price != null) {
                    counts.merge(svc, 1L, Long::sum);
                }
            }
        }

        // 4) Формируем DTO CartByServiceResponse с флагом allAvailable
        int totalProducts = productResponses.size();
        List<CartByServiceResponse> byService = sums.entrySet().stream()
                .map(e -> new CartByServiceResponse(
                        e.getKey(),
                        e.getValue(),
                        counts.getOrDefault(e.getKey(), 0L) == totalProducts  // true, если цена есть у всех товаров
                ))
                .toList();

        // 5) Возвращаем конечный объект CartResponse
        return new CartResponse(productResponses, byService);
    }

}