package etu.ru.deliverycosts.service.impl;

import etu.ru.deliverycosts.model.dto.response.CartByServiceResponse;
import etu.ru.deliverycosts.model.dto.response.CartResponse;
import etu.ru.deliverycosts.model.dto.response.ProductPriceResponse;
import etu.ru.deliverycosts.model.dto.response.ProductResponse;
import etu.ru.deliverycosts.model.entity.Product;
import etu.ru.deliverycosts.model.entity.ProductPrice;
import etu.ru.deliverycosts.repository.ProductPriceRepository;
import etu.ru.deliverycosts.repository.ProductRepository;
import etu.ru.deliverycosts.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {
    private final ProductRepository productRepo;
    private final ProductPriceRepository priceRepo;

    // в сессии храним только ID товаров
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
        // 1) получаем все продукты
        addProduct(98L);
        addProduct(3038L);
        addProduct(2161L);
        List<Product> products = productRepo.findAllById(cart);

        // 2) строим список ProductResponse
        List<ProductResponse> productResponses = products.stream().map(p -> {
            // достаём все цены для данного продукта
            List<ProductPrice> prices = priceRepo.findByProductId(p.getId());
            List<ProductPriceResponse> priceResponses = prices.stream()
                    .map(pp -> {
                        ProductPriceResponse pr = new ProductPriceResponse();
                        pr.setServiceName(pp.getService().getName());
                        pr.setPrice(pp.getPrice());
                        return pr;
                    })
                    .collect(Collectors.toList());

            ProductResponse resp = new ProductResponse();
            resp.setName(p.getName());
            resp.setPriceByService(priceResponses);
            return resp;
        }).collect(Collectors.toList());

        // 3) группируем по serviceName и суммируем цену каждого продукта
        Map<String, BigDecimal> totals = new HashMap<>();
        for (ProductResponse pr : productResponses) {
            for (ProductPriceResponse ppr : pr.getPriceByService()) {
                totals.merge(
                        ppr.getServiceName(),
                        ppr.getPrice(),
                        BigDecimal::add
                );
            }
        }

        // 4) формируем DTO для итогов по сервисам
        List<CartByServiceResponse> byService = totals.entrySet().stream()
                .map(e -> new CartByServiceResponse(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        return new CartResponse(productResponses, byService);
    }
}