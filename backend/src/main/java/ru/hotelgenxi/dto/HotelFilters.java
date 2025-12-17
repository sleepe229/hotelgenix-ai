package ru.hotelgenxi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotelFilters {
    private Integer minStars;
    private Integer maxStars;
    private Integer minPrice;
    private Integer maxPrice;
    private Boolean kidsClub;
    private Boolean allInclusive;
    private Boolean aquapark;
    private String country;
    private String city;
}
