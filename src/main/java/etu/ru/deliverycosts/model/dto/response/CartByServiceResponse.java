package etu.ru.deliverycosts.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class CartByServiceResponse {
    private String serviceName;
    private BigDecimal totalPrice;
    private boolean allAvailable;
}