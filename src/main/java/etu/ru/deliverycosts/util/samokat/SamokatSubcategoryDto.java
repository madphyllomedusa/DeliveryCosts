package etu.ru.deliverycosts.util.samokat;

import lombok.Data;

import java.util.List;

@Data
public class SamokatSubcategoryDto {
    private String uuid;
    private String name;
    private Integer position;
    private String type;       // "PRODUCT_CARD_COLLECTION" и т.п.
    private String displayType; // "TILE" ...
    private List<SamokatProductDto> products; // список товаров
}

