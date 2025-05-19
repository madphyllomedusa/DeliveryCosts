package etu.ru.deliverycosts.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CartResponse {
    private List<ProductResponse> products;
    private List<CartByServiceResponse> cartByService;
}
