package etu.ru.deliverycosts.service;

import etu.ru.deliverycosts.model.entity.Delivery;

public interface DeliveryService {
    Delivery findByName(String name);
}
