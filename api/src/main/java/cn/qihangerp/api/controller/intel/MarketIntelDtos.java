package cn.qihangerp.api.controller.intel;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

public final class MarketIntelDtos {
    private MarketIntelDtos() {}

    public record KeywordRequest(String keyword, Boolean enabled,
                                 @JsonProperty("sort_type") Integer sortType) {}

    public record CompetitorRequest(
            @JsonProperty("profile_url") String profileUrl,
            String nickname,
            @JsonProperty("red_id") String redId,
            @JsonProperty("avatar_url") String avatarUrl,
            Integer fans,
            Integer follows,
            String provider,
            @JsonProperty("account_id") String accountId) {}

    public record RunRequest(Long merchantId, String provider,
                             @JsonProperty("account_id") String accountId) {}

    public record NoteResult(
            @JsonProperty("note_id") String noteId,
            String source,
            List<String> keywords,
            String title,
            @JsonProperty("note_url") String noteUrl,
            @JsonProperty("cover_url") String coverUrl,
            @JsonProperty("user_id") String userId,
            String nickname,
            @JsonProperty("liked_count") Integer likedCount,
            @JsonProperty("collected_count") Integer collectedCount,
            @JsonProperty("comment_count") Integer commentCount,
            @JsonProperty("published_at") LocalDateTime publishedAt) {}

    public record CompetitorResult(
            @JsonProperty("user_id") String userId,
            String nickname,
            @JsonProperty("red_id") String redId,
            @JsonProperty("avatar_url") String avatarUrl,
            Integer fans,
            Integer follows,
            @JsonProperty("last_note_id") String lastNoteId) {}

    public record JobResult(
            @JsonProperty("request_token") String requestToken,
            String provider,
            @JsonProperty("account_id") String accountId,
            String status,
            List<NoteResult> notes,
            List<CompetitorResult> competitors,
            @JsonProperty("error_count") Integer errorCount,
            @JsonProperty("error_msg") String errorMsg) {}
}
