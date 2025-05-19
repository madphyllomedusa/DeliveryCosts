package etu.ru.deliverycosts.model.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(
        name = "product",
        indexes = {
                @Index(name = "idx_product_normalized_name", columnList = "normalized_name")
        }
)
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(name = "id", nullable = false)
    private Long id;

    /** Оригинальное имя товара, с пунктуацией и регистром */
    @Column(name = "name", nullable = false)
    private String name;

    /**
     * Нормализованное имя: lowercase + без пробелов и пунктуации.
     * По нему и будем искать, так можно избежать дублирования “чипсы 120г” vs “чипсы, 120г”.
     */
    @Column(name = "normalized_name", nullable = false, updatable = true)
    private String normalizedName;

    private String description;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductPrice> prices = new ArrayList<>();

    // Переопределяем сеттер из Lombok, чтобы пересчитывать normalizedName
    public void setName(String name) {
        this.name = name;
        this.normalizedName = normalize(name);
    }

    // Можно сделать private, чтобы никто не затирал normalizedName вручную
    private void setNormalizedName(String normalizedName) {
        this.normalizedName = normalizedName;
    }

    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        return input.toLowerCase()
                .replaceAll("\\p{Punct}", "")
                .replaceAll("\\s+", "");
    }
}

