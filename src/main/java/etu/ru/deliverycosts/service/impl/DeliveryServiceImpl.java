package etu.ru.deliverycosts.service.impl;

import etu.ru.deliverycosts.model.entity.Delivery;
import etu.ru.deliverycosts.repository.DeliveryRepository;
import etu.ru.deliverycosts.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class DeliveryServiceImpl implements DeliveryService {
    private final DeliveryRepository deliveryRepository;

    @Override
    public Delivery findByName(String name) {
        return deliveryRepository.findByName(name)
                .orElseThrow(()-> new RuntimeException("Служба доставки не найдена"));
    }
}
