/*
 * Copyright 2020 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.metric.collector.controller;

import com.navercorp.pinpoint.metric.collector.model.TelegrafMetric;
import com.navercorp.pinpoint.metric.collector.model.TelegrafMetrics;
import com.navercorp.pinpoint.metric.collector.service.SystemMetricDataTypeService;
import com.navercorp.pinpoint.metric.collector.service.SystemMetricService;
import com.navercorp.pinpoint.metric.collector.service.SystemMetricTagService;
import com.navercorp.pinpoint.metric.common.model.DoubleCounter;
import com.navercorp.pinpoint.metric.common.model.LongCounter;
import com.navercorp.pinpoint.metric.common.model.Metrics;
import com.navercorp.pinpoint.metric.common.model.SystemMetric;
import com.navercorp.pinpoint.metric.common.model.Tag;
import com.navercorp.pinpoint.metric.common.model.validation.SimpleErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Hyunjoon Cho
 */
@RestController
public class TelegrafMetricController {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final SystemMetricService systemMetricService;
    private final SystemMetricDataTypeService systemMetricMetadataService;
    private final SystemMetricTagService systemMetricTagService;

    private static final List<String> ignoreTags = Collections.singletonList("host");

    public TelegrafMetricController(SystemMetricService systemMetricService,
                                    SystemMetricDataTypeService systemMetricMetadataService,
                                    SystemMetricTagService systemMetricTagService) {
        this.systemMetricService = Objects.requireNonNull(systemMetricService, "systemMetricService");
        this.systemMetricMetadataService = Objects.requireNonNull(systemMetricMetadataService, "systemMetricMetadataService");
        this.systemMetricTagService = Objects.requireNonNull(systemMetricTagService, "systemMetricTagService");
    }


    @PostMapping(value = "/telegraf")
    public ResponseEntity<Void> saveSystemMetric(
            @RequestHeader(value = "Application-Name") String applicationName,
            @RequestBody TelegrafMetrics telegrafMetrics, BindingResult bindingResult
    ) throws BindException {
        if (bindingResult.hasErrors()) {
            SimpleErrorMessage simpleErrorMessage = new SimpleErrorMessage(bindingResult);
            logger.warn("metric binding error. header=Application-Name:{} errorCount:{} {}", applicationName, bindingResult.getErrorCount(), simpleErrorMessage);
            throw new BindException(bindingResult);
        }

        if (logger.isInfoEnabled()) {
            logger.info("Application-Name:{} size:{}", applicationName, telegrafMetrics.size());
        }
        logger.debug("telegrafMetrics:{}", telegrafMetrics);

        Metrics systemMetric = toMetrics(applicationName, telegrafMetrics);

        updateMetadata(systemMetric);
        systemMetricService.insert(systemMetric);

        return ResponseEntity.ok().build();
    }

    private Metrics toMetrics(String id, TelegrafMetrics telegrafMetrics) {
        List<TelegrafMetric> metrics = telegrafMetrics.getMetrics();

        List<SystemMetric> metricList = metrics.stream()
                .flatMap(this::toMetric)
                .collect(Collectors.toList());

        return new Metrics(id, metricList);
    }

    private Stream<SystemMetric> toMetric(TelegrafMetric tMetric) {
        Map<String, String> tTags = tMetric.getTags();
        List<Tag> tag = toTag(tTags, ignoreTags);
        final String host = tTags.get("host");
        final long timestamp = TimeUnit.SECONDS.toMillis(tMetric.getTimestamp());

        List<SystemMetric> metricList = new ArrayList<>();

        Map<String, Number> fields = tMetric.getFields();
        for (Map.Entry<String, Number> entry : fields.entrySet()) {
            SystemMetric systemMetric = null;

            Number value = entry.getValue();
            if (value instanceof Integer || value instanceof Long) {
                systemMetric = new LongCounter(tMetric.getName(), host, entry.getKey(), value.longValue(), tag, timestamp);
            }
            else if (value instanceof Float || value instanceof Double){
                systemMetric = new DoubleCounter(tMetric.getName(), host, entry.getKey(), value.doubleValue(), tag, timestamp);
            }

            metricList.add(systemMetric);
        }
        return metricList.stream();
    }



    private List<Tag> toTag(Map<String, String> tTags, List<String> ignoreTagName) {
        return tTags.entrySet().stream()
                .filter(entry -> ignoreTagName.contains(entry.getKey()))
                .map(this::newTag)
                .collect(Collectors.toList());
    }

    private Tag newTag(Map.Entry<String, String> entry) {
        return new Tag(entry.getKey(), entry.getValue());
    }

    private void updateMetadata(Metrics systemMetrics) {
        for (SystemMetric systemMetric : systemMetrics) {
            systemMetricMetadataService.saveMetricDataType(systemMetric);
            systemMetricTagService.saveMetricTag(systemMetrics.getId(), systemMetric);
        }

    }
}