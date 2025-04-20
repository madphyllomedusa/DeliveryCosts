package etu.ru.deliverycosts.model.dto.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductPriceResponse {
    private String serviceName;
    private BigDecimal price;
}
