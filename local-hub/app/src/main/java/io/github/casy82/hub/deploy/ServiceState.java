package io.github.casy82.hub.deploy;

import java.time.Instant;

import io.github.casy82.hub.git.GitClient.CommitInfo;

/** 대시보드에 보여줄 서비스 하나의 현재 상태. 불변 객체로 두고 바뀔 때마다 새로 만든다. */
public record ServiceState(
        String name,
        String repo,
        String branch,
        DeployStatus status,
        CommitInfo deployedCommit,
        CommitInfo failedCommit,
        Instant lastCheckedAt,
        Instant lastDeployedAt,
        String message,
        String lastOutput) {

    static ServiceState initial(String name, String repo, String branch) {
        return new ServiceState(name, repo, branch, DeployStatus.PENDING, null, null, null, null, "", "");
    }

    ServiceState checked() {
        return new ServiceState(name, repo, branch, status, deployedCommit, failedCommit, Instant.now(),
                lastDeployedAt, message, lastOutput);
    }

    ServiceState deploying(CommitInfo target) {
        return new ServiceState(name, repo, branch, DeployStatus.DEPLOYING, deployedCommit, failedCommit,
                Instant.now(), lastDeployedAt, target.shortSha() + " 배포 중", lastOutput);
    }

    ServiceState deployed(CommitInfo commit, String output) {
        return new ServiceState(name, repo, branch, DeployStatus.RUNNING, commit, null, Instant.now(),
                Instant.now(), commit.shortSha() + " 배포 완료", output);
    }

    ServiceState rolledBack(CommitInfo failed, CommitInfo restored, String output) {
        return new ServiceState(name, repo, branch, DeployStatus.ROLLED_BACK, restored, failed, Instant.now(),
                lastDeployedAt, failed.shortSha() + " 배포 실패, " + restored.shortSha() + " 로 되돌림", output);
    }

    ServiceState failed(CommitInfo failed, String message, String output) {
        return new ServiceState(name, repo, branch, DeployStatus.FAILED, deployedCommit, failed, Instant.now(),
                lastDeployedAt, message, output);
    }
}
