package etu.ru.deliverycosts.repository;

import etu.ru.deliverycosts.model.entity.ProductPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductPriceRepository extends JpaRepository<ProductPrice, Long> {
    List<ProductPrice> findByProductId(Long productId);
    @Query("""
        SELECT pp FROM ProductPrice pp
         WHERE pp.product.id   = :productId
           AND pp.service.id   = :serviceId
    """)
    Optional<ProductPrice> findByProductIdAndServiceId(
        @Param("productId") Long productId,
        @Param("serviceId") Long serviceId
    );
}
