package com.rewayaat.core;

import co.elastic.clients.elasticsearch._types.Refresh;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.data.HadithObject;

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
    }
}
