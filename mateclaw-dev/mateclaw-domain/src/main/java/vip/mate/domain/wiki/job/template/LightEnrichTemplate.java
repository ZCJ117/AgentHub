package vip.mate.domain.wiki.job.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vip.mate.domain.wiki.WikiProperties;
import vip.mate.domain.wiki.dto.WikiPageLite;
import vip.mate.domain.wiki.job.*;
import vip.mate.domain.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.domain.wiki.service.WikiCitationService;
import vip.mate.domain.wiki.service.WikiLinkEnrichmentService;
import vip.mate.domain.wiki.service.WikiRelationService;

import java.util.List;

/**
 * RFC-031: Light enrichment template — adds [[wikilinks]] and rebuilds
 * citations without regenerating page content.
 */
@Slf4j
@Component
public class LightEnrichTemplate extends WikiProcessingTemplate {

    private final WikiLinkEnrichmentService enrichmentService;
    private final WikiCitationService citationService;
    private final WikiRelationService relationService;

    public LightEnrichTemplate(WikiModelRoutingService routingService,
                                WikiProcessingJobService jobService,
                                ApplicationEventPublisher eventPublisher,
                                WikiProperties wikiProperties,
                                WikiLinkEnrichmentService enrichmentService,
                                WikiCitationService citationService,
                                WikiRelationService relationService) {
        super(routingService, jobService, eventPublisher, wikiProperties);
        this.enrichmentService = enrichmentService;
        this.citationService = citationService;
        this.relationService = relationService;
    }

    @Override
    protected WikiJobStep routingStep() { return WikiJobStep.ENRICH; }

    @Override
    protected WikiJobStage mainStage() { return WikiJobStage.ENRICHING; }

    @Override
    protected void doProcess(WikiProcessingJobEntity job, Long modelId) {
        List<WikiPageLite> pages = relationService.pagesByRawId(job.getRawId());
        for (WikiPageLite page : pages) {
            try {
                enrichmentService.enrichPage(page.id(), modelId);
                citationService.buildCitations(page.id(), job.getKbId());
            } catch (Exception e) {
                log.warn("[LightEnrich] Failed to enrich page {}: {}", page.slug(), e.getMessage());
            }
        }
    }
}
