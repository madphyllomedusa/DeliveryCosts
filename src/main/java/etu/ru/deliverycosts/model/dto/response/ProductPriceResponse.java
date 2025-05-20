package etu.ru.deliverycosts.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class ProductPriceResponse {
    private String serviceName;
    private BigDecimal price;

}
