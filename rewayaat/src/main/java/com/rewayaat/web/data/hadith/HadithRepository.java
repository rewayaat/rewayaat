package com.rewayaat.web.data.hadith;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Component;

@Component
public interface HadithRepository extends ElasticsearchRepository<HadithInfo, String> {

}
