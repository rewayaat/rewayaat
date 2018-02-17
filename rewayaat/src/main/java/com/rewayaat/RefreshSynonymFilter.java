package com.rewayaat;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.rewayaat.web.config.ClientProvider;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.RandomStringUtils;

import java.io.File;
import java.net.URL;

/**
 * Refreshes the ES Index to pick up synonym filter updates.
 */
public class RefreshSynonymFilter {

    public static void refresh() throws Exception {

        // delete existing and download new synonym file from github
        File synonymFile = new File(System.getenv("ES_CONFIG_SYNONYM_FILE"));
        boolean deleted = true;
            if (synonymFile.exists()) {
                deleted = synonymFile.delete();
            }
        if (deleted) {
            FileUtils.copyURLToFile(new URL("https://raw.githubusercontent.com/rewayaat/rewayaat/master/rewayaat/src/main/resources/synonyms.txt"), synonymFile);

            // get current index name
            HttpResponse resp = Unirest.get("http://" + ClientProvider.host + ":9200/_cat/indices").asString();
            String[] currIndexNames = ((String) resp.getBody()).split("\n");
            String currIndexName = "";
            for (String index : currIndexNames) {
                String indexName = index.split(" ")[2];
                if (indexName.startsWith("rewayaat")) {
                    currIndexName = indexName;
                    break;
                }
            }
            System.out.println(currIndexName);

            // generate new index name
            String newIndexName = "rewayaat_" + RandomStringUtils.randomAlphabetic(10).toLowerCase();

            // copy analyzer, mappings and data to new index
            Runtime rt = Runtime.getRuntime();
            Process pr = rt.exec("elasticdump   --input=http://" + ClientProvider.host + ":9200/" + currIndexName + "   --output=http://" + ClientProvider.host + ":9200/" + newIndexName + "   --type=analyzer");
            pr.waitFor();
            pr = rt.exec("elasticdump   --input=http://" + ClientProvider.host + ":9200/" + currIndexName + "   --output=http://" + ClientProvider.host + ":9200/" + newIndexName + "   --type=mapping");
            pr.waitFor();
            pr = rt.exec("elasticdump   --input=http://" + ClientProvider.host + ":9200/" + currIndexName + "   --output=http://" + ClientProvider.host + ":9200/" + newIndexName + "   --type=data");
            pr.waitFor();

            // remove alias to old index, add new alias
            Unirest.post("http://" + ClientProvider.host + ":9200/_aliases")
                    .body("{ \"actions\" : [ { \"remove\" : { \"index\" : \"" + currIndexName + "\", \"alias\" : \"rewayaat\" } } ] }").asString();

            Unirest.post("http://" + ClientProvider.host + ":9200/_aliases").body("{ \"actions\" : [ { \"add\" : { \"index\" : \"" + newIndexName + "\", \"alias\" : \"rewayaat\" } } ] }").asString();


            // delete old index
            Unirest.delete("http://" + ClientProvider.host + ":9200/" + currIndexName).asString();
        }
    }
}
