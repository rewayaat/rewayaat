package com.rewayaat.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;

import java.io.IOException;

public class ESClientProvider {

    public final static String INDEX = "rewayaat";
    private RestHighLevelClient client;

    public ESClientProvider() {
        prepareClient();
    }

    public void prepareClient() {
        RestHighLevelClient client = new RestHighLevelClient(RestClient.builder(
                new HttpHost(System.getenv("ELASTIC_HOST"), 9200)));
        this.client = client;
    }

    public void close() throws IOException {
        this.client.close();
    }

    public RestHighLevelClient client() {
        return client;
    }
}
