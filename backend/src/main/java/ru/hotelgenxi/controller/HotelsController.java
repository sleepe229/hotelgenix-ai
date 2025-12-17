package ru.hotelgenxi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.hotelgenxi.dto.HotelSearchResult;
import ru.hotelgenxi.dto.SearchRequest;
import ru.hotelgenxi.service.QdrantService;

import java.util.List;

@RestController
@RequestMapping("/api/hotels")
@CrossOrigin("*")
public class HotelsController {

    private final QdrantService qdrantService;

    public HotelsController(QdrantService qdrantService) {
        this.qdrantService = qdrantService;
    }

    @PostMapping("/search")
    public ResponseEntity<List<HotelSearchResult>> search(@RequestBody SearchRequest request) {
        try {
            List<HotelSearchResult> results = qdrantService.searchHotels(
                    request.getQuery(),
                    request.getFilters(),
                    request.getTopK()
            );
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            System.err.println("❌ Ошибка поиска: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
