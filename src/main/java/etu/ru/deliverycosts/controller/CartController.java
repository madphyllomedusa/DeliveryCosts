package etu.ru.deliverycosts.controller;

import etu.ru.deliverycosts.model.dto.response.CartResponse;
import etu.ru.deliverycosts.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;

    /** Добавить товар в корзину по ID */
    @PostMapping("/items/{productId}")
    public ResponseEntity<Void> addToCart(@PathVariable Long productId) {
        cartService.addProduct(productId);
        return ResponseEntity.ok().build();
    }

    /** Очистить корзину */
    @DeleteMapping
    public ResponseEntity<Void> clearCart() {
        cartService.clearCart();
        return ResponseEntity.noContent().build();
    }

    /** Получить корзину: список продуктов + итоги по каждому сервису */
    @GetMapping
    public ResponseEntity<CartResponse> getCart() {
        CartResponse resp = cartService.getCart();
        return ResponseEntity.ok(resp);
    }
}
