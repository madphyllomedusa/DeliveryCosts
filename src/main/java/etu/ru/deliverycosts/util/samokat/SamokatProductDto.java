package etu.ru.deliverycosts.util.samokat;

import lombok.Data;

import java.util.List;

@Data
public class SamokatProductDto {
    private String uuid;
    private String name;
    private SamokatPrices prices;
    private List<SamokatMedia> media;
    // ... при необходимости "highlights", "promoMarkup" и проч.
}
