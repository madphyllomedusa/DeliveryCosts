package etu.ru.deliverycosts.util.samokat;

import lombok.Data;

@Data
public class SamokatCategory {
    private String id;
    private String parentId;
    private String name;
    private String image;
    private Integer position;
}


