package ru.hotelgenxi.service;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class ResearchAgent {

    private static final Logger log = LoggerFactory.getLogger(ResearchAgent.class);
    private final GigaChatService gigaChatService;
    private final TavilySearchService tavilySearchService;

    public ResearchAgent(
            GigaChatService gigaChatService,
            TavilySearchService tavilySearchService
    ) {
        this.gigaChatService = gigaChatService;
        this.tavilySearchService = tavilySearchService;
    }

    /**
     * 🔧 TOOLS - будут вызваны GigaChat через function calling
     */

    @Tool("internet_search")
    public String searchInternet(String query) {
        log.info("[RESEARCH] 🔍 Searching: {}", query);
        String result = tavilySearchService.search(query);
        return result != null ? result : "Результаты не найдены";
    }

    @Tool("search_flights")
    public String searchFlights(String from, String to, String date) {
        log.info("[RESEARCH] ✈️ Searching flights {} → {} on {}", from, to, date);
        String query = String.format("авиабилеты %s %s %s цена рубли", from, to, date);
        return searchInternet(query);
    }

    @Tool("search_currency")
    public String getCurrencyRate(String from, String to) {
        log.info("[RESEARCH] 💱 Currency {} → {}", from, to);
        String query = String.format("курс %s к %s сегодня текущий", from, to);
        return searchInternet(query);
    }

    @Tool("search_weather")
    public String getWeather(String city, String date) {
        log.info("[RESEARCH] 🌤️ Weather {} on {}", city, date);
        String query = String.format("погода в %s на %s температура", city, date);
        return searchInternet(query);
    }

    @Tool("search_prices")
    public String searchPrices(String location, String date, String hotel) {
        log.info("[RESEARCH] 💰 Searching prices for {} in {} on {}", hotel, location, date);
        String query = String.format("цена отель %s %s %s", hotel, location, date);
        return searchInternet(query);
    }

    /**
     * 🎯 Главный метод - обработка запроса через GigaChat с tools
     */
    public void processResearchQuery(String userQuery, String sessionId) {
        String systemPrompt = buildSystemPrompt(userQuery);
        gigaChatService.streamResponseWithTools(userQuery, systemPrompt, sessionId);
    }

    /**
     * 📌 Системный промпт адаптируется к типу запроса
     */
    private String buildSystemPrompt(String query) {
        String lower = query.toLowerCase();

        if (lower.contains("погода") || lower.contains("температура") || lower.contains("климат")) {
            return """
                Ты специалист по погоде и климату. Используй инструмент 'search_weather' 
                для получения актуальной информации.
                Дай ТОЧНУЮ информацию о температуре, влажности, осадках.
                Ответ на русском с КОНКРЕТНЫМИ цифрами.
                """;
        }

        if (lower.contains("авиабилет") || lower.contains("перелет") || lower.contains("рейс")) {
            return """
                Ты агент по авиабилетам. Используй инструмент 'search_flights'
                для поиска текущих цен на авиабилеты.
                Дай реальные цены в рублях с датами вылета.
                """;
        }

        if (lower.contains("курс") || lower.contains("валюта") || lower.contains("доллар")) {
            return """
                Ты специалист по валютам. Используй инструмент 'search_currency'
                для получения актуального курса.
                Дай текущие курсы USD, EUR, TRY к RUB.
                """;
        }

        if (lower.contains("цена") || lower.contains("стоимость") || lower.contains("сколько")) {
            return """
                Ты агент по поиску цен. Используй инструмент 'search_prices'
                для поиска стоимости отелей и услуг.
                Дай точные цены с датами.
                """;
        }

        return """
            Ты research agent по путешествиям. 
            Используй доступные инструменты (internet_search, search_flights, search_currency, search_weather)
            для поиска информации в интернете.
            ВАЖНО: Вызывай инструменты когда нужна актуальная информация, цены, погода или новости.
            Не выдумывай данные - только используй результаты поиска!
            """;
    }
}
