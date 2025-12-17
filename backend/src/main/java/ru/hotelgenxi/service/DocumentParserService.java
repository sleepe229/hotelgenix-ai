package ru.hotelgenxi.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.awt.image.BufferedImage;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    public String parseDocument(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        log.info("[PARSER] Parsing: {}", filename);

        if (filename == null) {
            throw new IllegalArgumentException("Имя файла не может быть пустым");
        }

        if (filename.endsWith(".pdf")) {
            return parsePDF(file); // ✅ ИСПОЛЬЗУЕМ PDFBOX НАПРЯМУЮ
        } else if (filename.endsWith(".docx")) {
            return parseDOCX(file);
        } else if (filename.endsWith(".txt")) {
            return parseTXT(file);
        } else {
            throw new IllegalArgumentException("Неподдерживаемый формат: " + filename);
        }
    }

    /**
     * 📑 Парсинг PDF через PDFBox 3.0.1
     */
    private String parsePDF(MultipartFile file) throws IOException {
        log.info("[PDFBOX] Parsing PDF: {}", file.getOriginalFilename());

        try {
            byte[] pdfBytes = file.getBytes();

            try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(pdfBytes))) {
                PDFTextStripper stripper = new PDFTextStripper();

                // 🔧 Важные настройки
                stripper.setAddMoreFormatting(true);
                stripper.setShouldSeparateByBeads(true);
                stripper.setSortByPosition(true);

                String text = stripper.getText(document);

                log.info("[PDFBOX] ✅ Extracted {} chars", text.length());

                // 🔍 DEBUG: Показываем первые 1000 символов
                log.warn("[PDFBOX] 📄 First 1000 chars:\n{}",
                        text.substring(0, Math.min(1000, text.length())));

                // 🔍 DEBUG: Ищем ВСЕ числа 4-6 цифр
                Pattern digitPattern = Pattern.compile("\\d{4,6}");
                Matcher matcher = digitPattern.matcher(text);
                List<String> allNumbers = new ArrayList<>();
                while (matcher.find()) {
                    allNumbers.add(matcher.group());
                }
                log.warn("[PDFBOX] 🔢 Found {} numbers: {}", allNumbers.size(), allNumbers);

                return text;
            }
        } catch (Exception e) {
            log.error("[PDFBOX] ❌ Error: {}", e.getMessage(), e);
            throw new IOException("Ошибка парсинга PDF: " + e.getMessage(), e);
        }
    }

    /**
     * 📋 Парсинг DOCX через Tika (только для DOCX!)
     */
    private String parseDOCX(MultipartFile file) throws IOException {
        log.info("[TIKA] Parsing DOCX: {}", file.getOriginalFilename());

        try {
            Tika tika = new Tika();
            String text = tika.parseToString(file.getInputStream());
            log.info("[TIKA] ✅ Extracted {} chars from DOCX", text.length());
            return text;
        } catch (Exception e) {
            log.error("[TIKA] ❌ Error: {}", e.getMessage());
            throw new IOException("Ошибка парсинга DOCX: " + e.getMessage(), e);
        }
    }

    private String parseTXT(MultipartFile file) throws IOException {
        log.info("[PARSER] Parsing TXT: {}", file.getOriginalFilename());
        String text = new String(file.getBytes());
        log.info("[PARSER] ✅ Read {} chars from TXT", text.length());
        return text;
    }

    private Map<String, String> extractHotelInfo(String rawText) {
        String text = normalizeText(rawText);
        Map<String, String> info = new HashMap<>();

        for (String line : text.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty()) continue;

            if (l.endsWith("*") && l.length() <= 80 && !l.contains("РЕКЛАМА")) {
                Matcher m = Pattern.compile("^(.+?)\\s*(\\d+)?\\*$").matcher(l);
                if (m.find()) {
                    String name = m.group(1).trim();
                    String stars = m.group(2);

                    if (name.length() >= 2 && name.matches(".*\\p{IsCyrillic}.*")) {
                        info.put("hotelName", name);
                        if (stars != null) info.put("stars", stars);
                        log.warn("[PARSER] 🏨 Found hotel: {} {}", name, stars != null ? stars + "*" : "");
                        break;
                    }
                }
            }
        }

        info.putIfAbsent("city", "Москва");
        info.putIfAbsent("country", "Россия");
        return info;
    }

    public List<Integer> extractPrices(String rawText) {
        String text = normalizeText(rawText);

        int idx = text.indexOf("Цены на ближайшие даты:");
        String slice = (idx >= 0) ? text.substring(idx, Math.min(idx + 3000, text.length())) : text;

        log.warn("[PARSER] 🔍 Searching prices in {} chars", slice.length());

        List<Integer> prices = new ArrayList<>();

        Pattern p = Pattern.compile("от\\s*([0-9][0-9 \\u00A0]{2,10})\\s*(?:₽|р\\b|руб\\b|руб\\.)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(slice);

        while (m.find()) {
            String digits = m.group(1).replaceAll("[^0-9]", "");
            if (digits.isEmpty()) continue;

            int price = Integer.parseInt(digits);
            if (price >= 500 && price <= 500000) {
                prices.add(price);
                log.warn("[PARSER] 💰 Found price: {} ₽", price);
            }
        }

        log.warn("[PARSER] 📊 Total prices: {}", prices.size());
        prices.sort(Integer::compareTo);
        return prices;
    }

    public Map<String, Object> compareDocuments(String text1, String text2) {
        Map<String, String> info1 = extractHotelInfo(text1);
        Map<String, String> info2 = extractHotelInfo(text2);

        List<Integer> prices1 = extractPrices(text1);
        List<Integer> prices2 = extractPrices(text2);

        Integer p1 = prices1.isEmpty() ? null : prices1.get(0);
        Integer p2 = prices2.isEmpty() ? null : prices2.get(0);

        info1.put("pricePerNight", p1 == null ? "N/A" : String.valueOf(p1));
        info2.put("pricePerNight", p2 == null ? "N/A" : String.valueOf(p2));

        Map<String, Object> result = new HashMap<>();
        result.put("hotel1", info1);
        result.put("hotel2", info2);
        result.put("price1", p1);
        result.put("price2", p2);

        if (p1 != null && p2 != null) {
            result.put("difference", Math.abs(p1 - p2));
            result.put("cheaper", p1 <= p2 ? "hotel1" : "hotel2");
        } else {
            result.put("difference", null);
            result.put("cheaper", null);
        }

        log.warn("[PARSER] 🏁 Result: {} vs {}", info1, info2);
        return result;
    }

    public List<Integer> extractPricesWithOcr(MultipartFile file) throws IOException {
        log.warn("[OCR] Fallback: trying to extract prices via OCR for {}", file.getOriginalFilename());
        byte[] pdfBytes = file.getBytes();
        List<Integer> prices = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(pdfBytes))) {
            PDFRenderer renderer = new PDFRenderer(document);

            ITesseract tesseract = new Tesseract();
            tesseract.setLanguage("rus+eng"); // нужно наличие rus/eng в tessdata

            int pages = Math.min(3, document.getNumberOfPages()); // хватит 1–3 страниц

            for (int page = 0; page < pages; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 300); // 300 DPI для OCR
                String ocrText = tesseract.doOCR(image);

                log.warn("[OCR] Page {} text (first 800 chars):\n{}",
                        page, ocrText.substring(0, Math.min(800, ocrText.length())));

                List<Integer> pagePrices = extractPricesFromPlainText(ocrText);
                if (!pagePrices.isEmpty()) {
                    log.warn("[OCR] Page {} prices: {}", page, pagePrices);
                    prices.addAll(pagePrices);
                }
            }
        } catch (Exception e) {
            log.error("[OCR] ❌ Error while extracting prices: {}", e.getMessage(), e);
        }

        prices = prices.stream().distinct().sorted().toList();
        log.warn("[OCR] ✅ Final prices from OCR: {}", prices);
        return prices;
    }

    private List<Integer> extractPricesFromPlainText(String rawText) {
        String text = normalizeText(rawText);

        int idx = text.indexOf("Цены на ближайшие даты:");
        String slice = (idx >= 0) ? text.substring(idx, Math.min(idx + 4000, text.length())) : text;

        log.warn("[PARSER] 🔍 Searching prices in {} chars", slice.length());

        List<Integer> prices = new ArrayList<>();

        Pattern p = Pattern.compile(
                "(?:от|цена|стоимость)\\s+([0-9]{3,6}(?:\\s*[0-9]{3})*)",
                Pattern.CASE_INSENSITIVE
        );
        Matcher m = p.matcher(slice);

        while (m.find()) {
            String digits = m.group(1).replaceAll("[^0-9]", "");
            if (digits.isEmpty()) continue;

            int price = Integer.parseInt(digits);
            if (price >= 500 && price <= 500000) {
                prices.add(price);
                log.warn("[PARSER] 💰 Found price: {} ₽", price);
            }
        }

        return prices;
    }

    private static String normalizeText(String s) {
        if (s == null) return "";
        return s
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2009', ' ')
                .replace('\u2007', ' ')
                .replace('\u2006', ' ')
                .replaceAll("[ \\t\\x0B\\f]+", " ");
    }

    public List<Integer> extractPricesWithOcrFromBytes(byte[] pdfBytes) throws IOException {
        log.warn("[OCR] Extracting prices via OCR from bytes");
        List<Integer> prices = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(pdfBytes))) {
            PDFRenderer renderer = new PDFRenderer(document);

            ITesseract tesseract = new Tesseract();

            // 🔧 ВОТ ЭТО ДОБАВИТЬ!
            tesseract.setDatapath("C:\\Program Files\\Tesseract-OCR\\tessdata");
            tesseract.setLanguage("rus+eng");

            int pages = Math.min(5, document.getNumberOfPages());

            for (int page = 0; page < pages; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 300);
                String ocrText = tesseract.doOCR(image);

                log.warn("[OCR] Page {} text (first 800 chars):\n{}",
                        page, ocrText.substring(0, Math.min(800, ocrText.length())));

                List<Integer> pagePrices = extractPricesFromPlainText(ocrText);
                if (!pagePrices.isEmpty()) {
                    log.warn("[OCR] Page {} prices: {}", page, pagePrices);
                    prices.addAll(pagePrices);
                }
            }
        } catch (Exception e) {
            log.error("[OCR] ❌ Error while extracting prices: {}", e.getMessage(), e);
        }

        prices = prices.stream().distinct().sorted().toList();
        log.warn("[OCR] ✅ Final prices from OCR: {}", prices);
        return prices;
    }


}
