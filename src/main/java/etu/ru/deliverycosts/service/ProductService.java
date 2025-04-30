package etu.ru.deliverycosts.service;

import etu.ru.deliverycosts.model.entity.Delivery;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;

public interface ProductService {
    void updateOrSaveProduct(Delivery delivery,
                             String originalName,
                             BigDecimal price);
}
