package com.rewayaat.core;

import co.elastic.clients.elasticsearch._types.Refresh;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.data.HadithObject;
import com.rewayaat.service.CatalogSignal;

/**
 * Updates a given hadith in the hadith database.
 */
public class UpdateRequest {

    private final HadithObject newHadithObject;
    private final String hadithId;

    public UpdateRequest(HadithObject newHadithObject, String hadithId) {
        this.newHadithObject = newHadithObject;
        this.hadithId = hadithId;
    }

    public void execute() throws Exception {
        try (ESClientProvider provider = new ESClientProvider()) {
            provider.client().index(i -> i
                    .index(ESClientProvider.INDEX)
                    .id(hadithId)
                    .document(newHadithObject)
                    .refresh(Refresh.True));
        }
        // Book, part and chapter pages are served from a cached catalog that nothing else
        // would tell about this edit. A renamed chapter changes its own slug, so without
        // this the new URL does not resolve and the old one is still linked - the chapter
        // looks deleted. Recorded after the write, so a failed edit does not invalidate.
        CatalogSignal.touch();
    }
}
