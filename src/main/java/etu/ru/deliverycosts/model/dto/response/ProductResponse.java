package etu.ru.deliverycosts.model.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class ProductResponse {
    private Long id;
    private String name;
    private String description;
    private List<ProductPriceResponse> priceByService;
}
