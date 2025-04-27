package etu.ru.deliverycosts.controller;

import etu.ru.deliverycosts.service.impl.FiveKaUpdateService;
import etu.ru.deliverycosts.service.impl.LentaUpdateService;
import etu.ru.deliverycosts.service.impl.SamokatUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TestController {

    private final SamokatUpdateService samokatUpdateService;
    private final FiveKaUpdateService fiveKaUpdateService;

    private final LentaUpdateService lentaUpdateService;
    @GetMapping("/test")
    public void getCategories() {
        fiveKaUpdateService.updateFiveKaData();
        //lentaUpdateService.updateLentaData();
        //samokatUpdateService.updateSamokatData();
    }
}

