package etu.ru.deliverycosts.model.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class ProductResponse {
    private String name;
    private List<ProductPriceResponse> priceByService;
}
