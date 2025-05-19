package etu.ru.deliverycosts.service;

import etu.ru.deliverycosts.model.dto.response.CartResponse;

public interface CartService {
    void addProduct(Long productId);
    CartResponse getCart();
    void clearCart();
}