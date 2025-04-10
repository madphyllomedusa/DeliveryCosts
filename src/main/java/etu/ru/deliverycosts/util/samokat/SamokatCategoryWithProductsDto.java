package etu.ru.deliverycosts.util.samokat;

import lombok.Data;

import java.util.List;

@Data
public class SamokatCategoryWithProductsDto {
    private String uuid;
    private String name;
    private String image;
    private Integer position;
    private List<SamokatSubcategoryDto> categories; // вложенные подкатегории
}