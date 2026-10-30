package io.github.casy82.hub.issue;

import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.casy82.hub.config.HubProperties;

/** hub.issues.enabled=true 일 때만 이슈 보고를 켠다 */
@Configuration
@ConditionalOnProperty(prefix = "hub.issues", name = "enabled", havingValue = "true")
public class IssueConfig {

    private static final Logger log = LoggerFactory.getLogger(IssueConfig.class);

    @Bean
    FailureIssueReporter failureIssueReporter(HubProperties props) {
        HubProperties.Issues issues = props.issues();
        if (issues.token().isBlank()) {
            throw new IllegalStateException("hub.issues.enabled=true 인데 hub.issues.token 이 비어 있습니다");
        }
        if (issues.labels().isEmpty()) {
            throw new IllegalStateException("hub.issues.labels 에 라벨이 하나 이상 있어야 합니다");
        }
        for (HubProperties.Service s : props.services()) {
            if (s.issueRepoOrDerived().isEmpty()) {
                log.warn("[{}] GitHub 저장소가 아니어서 이슈를 올리지 않습니다. 올리려면 issue-repo 를 설정하세요", s.name());
            }
        }
        return new FailureIssueReporter(issues, new GitHubIssueTracker(issues),
                Executors.newVirtualThreadPerTaskExecutor());
    }
}
