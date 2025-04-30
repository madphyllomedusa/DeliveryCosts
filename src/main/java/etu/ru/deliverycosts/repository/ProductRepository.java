package etu.ru.deliverycosts.repository;

import etu.ru.deliverycosts.model.entity.Delivery;
import etu.ru.deliverycosts.model.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    @Query(nativeQuery = true,
           value = "SELECT * FROM product WHERE regexp_replace(name, '[^\\w]', '', 'g') = ?1")
    Optional<Product> findByNameIgnorePunctuation(String cleanedName);

    default Optional<Product> findByNameCleaned(String name) {
        String cleanedName = name.replaceAll("[\\W]", "").toLowerCase();
        return findByNameIgnorePunctuation(cleanedName);
    }

    @Query("SELECT p FROM Product p JOIN p.prices pp WHERE p.name = :name AND pp.service = :delivery")
    Optional<Product> findByNameAndPrices_Service(@Param("name") String name,
                                               @Param("delivery") Delivery delivery);

    Optional <Product> findByName(String name);

    Optional<Product> findByNormalizedName(String normalizedName);

}
