package com.rewayaat.controllers.rest;

import com.rewayaat.core.BrowseFacets;
import com.rewayaat.service.BookCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * API for browsing books and sections.
 */
@Service
@org.springframework.stereotype.Controller
@RequestMapping("/v1/browse")
public class BrowseController {

    private final BookCatalog catalog;

    public BrowseController(BookCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * The server-rendered page a browse selection corresponds to.
     *
     * <p>The browse panel used to submit into the search app's reading mode. It sends
     * readers to the book, volume or chapter page instead, and asks for the URL rather
     * than building it, because the slugs come from {@link BookCatalog#slugify} and a
     * second implementation in the browser would drift from the routes.
     *
     * <p>Answers {@code {"ok": false}} when the selection has no page of its own, which
     * is the caller's cue to fall back to a search.
     */
    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Resolves a browse selection to its server-rendered page.")
    @RequestMapping(value = "/page", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public Map<String, Object> page(
            @RequestParam(value = "book", required = false) String book,
            @RequestParam(value = "volume", required = false) String volume,
            @RequestParam(value = "part", required = false) String part,
            @RequestParam(value = "section", required = false) String section,
            @RequestParam(value = "chapter", required = false) String chapter) {

        if (book == null || book.isBlank()) {
            return Map.of("ok", false);
        }

        Optional<BookCatalog.Book> found = catalog.bookByName(book.trim());
        if (found.isEmpty()) {
            return Map.of("ok", false);
        }
        BookCatalog.Book resolved = found.get();

        // Every level that has a page, not just the deepest one. A card's metadata rows
        // each link somewhere different, and one round trip beats three.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);

        String bookUrl = "/books/" + resolved.slug();
        out.put("bookUrl", bookUrl);

        String volumeUrl = null;
        if (volume != null && !volume.isBlank() && resolved.volumes().contains(volume.trim())) {
            volumeUrl = bookUrl + "/volume/"
                    + URLEncoder.encode(volume.trim(), StandardCharsets.UTF_8).replace("+", "%20");
            out.put("volumeUrl", volumeUrl);
        }

        String chapterUrl = null;
        if (chapter != null && !chapter.isBlank()) {
            chapterUrl = catalog.chapterFor(book.trim(), volume, part, section, chapter.trim())
                    .map(BookCatalog.Chapter::url).orElse(null);
            if (chapterUrl != null) {
                out.put("chapterUrl", chapterUrl);
            }
        }

        // "url" stays the deepest page that exists, which is what the browse panel and
        // the metadata rows navigate to.
        out.put("url", chapterUrl != null ? chapterUrl : volumeUrl != null ? volumeUrl : bookUrl);
        return out;
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Returns all books with counts.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Returns all books with counts."),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @RequestMapping(value = "/books", method = RequestMethod.GET, produces = "application/json")
    public ResponseEntity<String> books() throws Exception {
        return new ResponseEntity<>(new BrowseFacets().books().toString(), HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Returns facets (section, chapter, volume, part) for a given book.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Returns facets for a given book."),
            @ApiResponse(responseCode = "400", description = "Bad request"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @RequestMapping(value = "/facets", method = RequestMethod.GET, produces = "application/json")
    public ResponseEntity<String> facets(
            @Parameter(name = "book", description = "Book name to fetch facets for.")
            @RequestParam(value = "book", required = true) String book,
            @Parameter(name = "volume", description = "Optional volume filter.")
            @RequestParam(value = "volume", required = false) String volume,
            @Parameter(name = "part", description = "Optional part filter.")
            @RequestParam(value = "part", required = false) String part,
            @Parameter(name = "section", description = "Optional section filter.")
            @RequestParam(value = "section", required = false) String section,
            @Parameter(name = "chapter", description = "Optional chapter filter.")
            @RequestParam(value = "chapter", required = false) String chapter) throws Exception {
        if (book == null || book.trim().isEmpty()) {
            return new ResponseEntity<>("Book is required.", HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(new BrowseFacets().facets(book, volume, part, section, chapter).toString(),
                HttpStatus.OK);
    }
}
