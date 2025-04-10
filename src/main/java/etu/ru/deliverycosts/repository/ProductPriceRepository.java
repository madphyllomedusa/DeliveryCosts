package etu.ru.deliverycosts.repository;

import etu.ru.deliverycosts.model.entity.ProductPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductPriceRepository extends JpaRepository<ProductPrice, Long> {
}
