package ru.hotelgenxi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class HotelSearchResult {
    private String id;
    private String name;
    private String country;
    private String city;
    private String address;
    private Integer stars;
    private Double pricePerNight;
    private Double rating;
    private String description;
    private Double similarity;

    // ✅ Удобства
    private Boolean kidsClub;
    private Boolean allInclusive;
    private Boolean aquapark;

    // ✅ Дополнительные поля (для карточек)
    private String imageUrl;
    private List<String> amenities;
    private List<ReviewDTO> reviews;
}