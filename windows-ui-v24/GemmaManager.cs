using System.Text;
using System.Text.Json;

namespace AIFantasyFootballV2;

public sealed class GemmaDraftBoardDecision
{
    public string Strategy { get; set; } = "";
    public List<string> PlayerIds { get; set; } = new();
}

public sealed class GemmaDraftPickDecision
{
    public string PlayerId { get; set; } = "";
    public string Reason { get; set; } = "";
}

public sealed class GemmaRosterPlanDecision
{
    public List<GemmaWaiverDecision> WaiverClaims { get; set; } = new();
    public List<GemmaTradeProposal> TradeProposals { get; set; } = new();
    public string Summary { get; set; } = "";
}

public sealed class GemmaWaiverDecision
{
    public string AddPlayerId { get; set; } = "";
    public string DropPlayerId { get; set; } = "";
    public int Priority { get; set; } = 1;
    public string Reason { get; set; } = "";
}

public sealed class GemmaTradeProposal
{
    public int ToTeamId { get; set; }
    public string GivePlayerId { get; set; } = "";
    public string ReceivePlayerId { get; set; } = "";
    public string Reason { get; set; } = "";
}

public sealed class GemmaLineupDecision
{
    public Dictionary<string, string> Lineup { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public string Reasoning { get; set; } = "";
}

public sealed class GemmaTradeDecision
{
    public string Decision { get; set; } = "REJECT";
    public string Reason { get; set; } = "";
    public string CounterGivePlayerId { get; set; } = "";
    public string CounterReceivePlayerId { get; set; } = "";
}

public sealed class GemmaManagerService
{
    private readonly AiService _ai;
    public GemmaManagerService(AiService ai) => _ai = ai;

    public async Task<GemmaDraftBoardDecision?> BuildDraftBoardAsync(LeagueState s, FantasyTeam team, CancellationToken ct)
    {
        var candidates = s.Players.Values
            .Where(p => p.Active && LeagueEngine.FantasyPos(p.Position))
            .OrderBy(p => p.SearchRank).ThenByDescending(p => p.SeasonFantasyPoints)
            .Take(160).ToList();
        var valid = candidates.Select(p => p.Id).ToHashSet(StringComparer.OrdinalIgnoreCase);
        string candidateText = PlayerLines(candidates);
        string caps = "Roster caps: QB max 2, TE max 3, K max 2, DEF max 2. The final 15-player roster must be capable of filling QB, 2 RB, 2 WR, TE, FLEX, K, DEF and 6 bench spots.";
        string system = $$"""
You are {{team.OwnerName}}, the actual general manager of {{team.TeamName}} in a 6-team half-PPR fantasy football league.
Personality: {{team.Personality}}
Risk tolerance: {{team.RiskTolerance}}/100.
You—not a formula—are responsible for this team's football decisions. Build your own ranked draft board according to your philosophy, player quality, positional scarcity, injuries, roster construction, and upside/floor preferences.
{{caps}}
Return JSON only, with exactly this shape:
{"strategy":"one short sentence","playerIds":["id1","id2",...]}
Use ONLY the supplied player IDs. Return at least 85 unique player IDs, ordered from most preferred to least preferred. Include enough late-round K and DEF options that a legal complete roster can still be drafted. Do not use markdown.
""";
        string user = $"Season {s.Season}. Current consensus player pool:\n{candidateText}";

        for (int attempt = 0; attempt < 2; attempt++)
        {
            var board = await _ai.ChatJsonAsync<GemmaDraftBoardDecision>(s, system, user, ct, 2500, .55);
            if (board == null) continue;
            board.PlayerIds = board.PlayerIds.Where(valid.Contains).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            if (board.PlayerIds.Count >= 55) return board;
            user = "Your previous board did not contain enough valid supplied IDs. Return JSON only and include at least 85 unique IDs from this exact pool.\n" + candidateText;
        }
        return null;
    }

    public async Task<GemmaDraftPickDecision?> ChooseEmergencyDraftPickAsync(LeagueState s, FantasyTeam team, int round, IEnumerable<Player> available, CancellationToken ct)
    {
        var list = available.Take(40).ToList();
        if (list.Count == 0) return null;
        string roster = RosterLines(s, team);
        string system = $$"""
You are {{team.OwnerName}}, general manager of {{team.TeamName}}. Personality: {{team.Personality}}.
Your pre-draft board has been exhausted at this pick, so YOU must choose the replacement pick. The league engine will only validate legality.
Return JSON only: {"playerId":"one supplied id","reason":"short reason"}. Use only an AVAILABLE player ID. Do not use markdown.
""";
        string user = $"Round {round}. Current roster:\n{roster}\n\nAVAILABLE candidates:\n{PlayerLines(list)}";
        var decision = await _ai.ChatJsonAsync<GemmaDraftPickDecision>(s, system, user, ct, 450, .5);
        if (decision == null) return null;
        if (!list.Any(p => p.Id.Equals(decision.PlayerId, StringComparison.OrdinalIgnoreCase))) return null;
        return decision;
    }

    public async Task<GemmaRosterPlanDecision?> BuildRosterPlanAsync(LeagueState s, FantasyTeam team, DataRefreshResult data, CancellationToken ct)
    {
        var freeAgents = LeagueEngine.FreeAgents(s).Take(75).ToList();
        var matchup = s.Matchups.FirstOrDefault(m => m.Week == s.CurrentWeek && (m.HomeTeamId == team.Id || m.AwayTeamId == team.Id));
        FantasyTeam? opp = null;
        if (matchup != null)
        {
            int oppId = matchup.HomeTeamId == team.Id ? matchup.AwayTeamId : matchup.HomeTeamId;
            opp = s.Teams.FirstOrDefault(t => t.Id == oppId);
        }
        string standings = string.Join("\n", LeagueEngine.Standings(s).Select((t, i) => $"{i + 1}. teamId={t.Id} {t.TeamName} {t.Record} PF={t.PointsFor:0.0}"));
        string others = string.Join("\n\n", s.Teams.Where(t => t.Id != team.Id).Select(t => $"TEAM {t.Id} {t.TeamName} ({t.OwnerName})\n{RosterLines(s, t, tradableOnly: true)}"));
        string headlines = data.Headlines.Count == 0 ? "No fresh headline feed available." : string.Join("\n", data.Headlines.Take(8));
        string warnings = data.Warnings.Count == 0 ? "None" : string.Join(" | ", data.Warnings);

        string system = $$"""
You are {{team.OwnerName}}, the actual autonomous general manager of {{team.TeamName}} in a 6-team half-PPR fantasy league.
Personality: {{team.Personality}}
Risk tolerance: {{team.RiskTolerance}}/100.
You directly control this AI franchise. Decide whether the roster should make waiver claims and whether YOU want to propose a one-for-one trade. It is completely valid to make no move. Do not make moves just to be active.
The league engine will only enforce legality and waiver priority; it will NOT substitute its own football judgment.
Return JSON only in this shape:
{"waiverClaims":[{"addPlayerId":"id","dropPlayerId":"id or empty","priority":1,"reason":"short"}],"tradeProposals":[{"toTeamId":1,"givePlayerId":"id","receivePlayerId":"id","reason":"short"}],"summary":"short GM summary"}
Rules: maximum 3 waiver claims in priority order; maximum 1 trade proposal from you this management cycle; only use supplied player/team IDs; do not trade K or DEF; do not invent players; empty arrays are encouraged when no move improves your team. Do not use markdown.
""";
        string user = $$"""
Fantasy Week {{s.CurrentWeek}}, season {{s.Season}}.
Your record: {{team.Record}}. This week's opponent: {{(opp == null ? "unknown" : opp.TeamName + " " + opp.Record)}}.
DATA WARNINGS: {{warnings}}

YOUR ROSTER:
{{RosterLines(s, team)}}

TOP AVAILABLE FREE AGENTS:
{{PlayerLines(freeAgents)}}

LEAGUE STANDINGS:
{{standings}}

OTHER TEAM ROSTERS (tradeable positions shown):
{{others}}

CURRENT NFL HEADLINES:
{{headlines}}
""";
        return await _ai.ChatJsonAsync<GemmaRosterPlanDecision>(s, system, user, ct, 1300, .62);
    }

    public async Task<GemmaLineupDecision?> BuildLineupDecisionAsync(LeagueState s, FantasyTeam team, DataRefreshResult? data, CancellationToken ct, string correction = "")
    {
        var matchup = s.Matchups.FirstOrDefault(m => m.Week == s.CurrentWeek && (m.HomeTeamId == team.Id || m.AwayTeamId == team.Id));
        FantasyTeam? opp = null;
        if (matchup != null)
        {
            int oppId = matchup.HomeTeamId == team.Id ? matchup.AwayTeamId : matchup.HomeTeamId;
            opp = s.Teams.FirstOrDefault(t => t.Id == oppId);
        }
        string headlines = data == null || data.Headlines.Count == 0 ? "No fresh headlines supplied." : string.Join("\n", data.Headlines.Take(8));
        string system = $$"""
You are {{team.OwnerName}}, the actual head coach/general manager of {{team.TeamName}}. Personality: {{team.Personality}}.
YOU choose the starting lineup. Consider injury status, opponent, recent production, season production, projection/rank as imperfect inputs, player news, floor/upside, and your own managerial philosophy. The code will only validate position eligibility and game locks; it will not choose starters for you.
Return JSON only with exactly these nine keys inside lineup: QB,RB1,RB2,WR1,WR2,TE,FLEX,K,DEF. Every value must be a player ID from YOUR ROSTER. Use each player at most once. FLEX must be RB/WR/TE.
Shape: {"lineup":{"QB":"id","RB1":"id","RB2":"id","WR1":"id","WR2":"id","TE":"id","FLEX":"id","K":"id","DEF":"id"},"reasoning":"one or two concise sentences"}
Do not use markdown.
""";
        string user = $$"""
Week {{s.CurrentWeek}}. Record {{team.Record}}. Opponent: {{(opp == null ? "unknown" : opp.TeamName + " " + opp.Record)}}.
YOUR ROSTER (LOCKED means the NFL game has started and that player's current slot cannot be changed):
{{RosterLines(s, team, includeLock: true)}}
CURRENT NFL HEADLINES:
{{headlines}}
{{(string.IsNullOrWhiteSpace(correction) ? "" : "\nCORRECTION REQUIRED FROM YOUR PREVIOUS LINEUP: " + correction)}}
""";
        return await _ai.ChatJsonAsync<GemmaLineupDecision>(s, system, user, ct, 750, .48);
    }

    public async Task<GemmaTradeDecision?> EvaluateAiTradeAsync(LeagueState s, FantasyTeam target, FantasyTeam proposer, string proposerGivesId, string targetGivesId, CancellationToken ct)
    {
        var give = LeagueEngine.PlayerById(s, proposerGivesId);
        var receive = LeagueEngine.PlayerById(s, targetGivesId);
        if (give == null || receive == null) return null;
        string system = $$"""
You are {{target.OwnerName}}, autonomous GM of {{target.TeamName}}. Personality: {{target.Personality}}. Risk tolerance {{target.RiskTolerance}}/100.
Another AI owner proposed a trade. YOU decide whether your franchise accepts, rejects, or counters. The engine does not judge fairness for you; it only checks legality.
Return JSON only: {"decision":"ACCEPT|REJECT|COUNTER","reason":"short","counterGivePlayerId":"id or empty","counterReceivePlayerId":"id or empty"}.
If COUNTER: counterGivePlayerId must be a QB/RB/WR/TE from YOUR roster and counterReceivePlayerId must be a QB/RB/WR/TE from the proposer's roster. If not countering, leave both counter IDs empty. Do not use markdown.
""";
        string user = $$"""
Week {{s.CurrentWeek}}. {{proposer.OwnerName}} ({{proposer.TeamName}}) offers you {{give.Name}} ({{give.Position}}, rank {{give.SearchRank}}, proj {{give.Projection:0.0}}, season {{give.SeasonFantasyPoints:0.0}}, last {{give.LastWeekFantasyPoints:0.0}}, injury {{Safe(give.InjuryStatus)}}) for your {{receive.Name}} ({{receive.Position}}, rank {{receive.SearchRank}}, proj {{receive.Projection:0.0}}, season {{receive.SeasonFantasyPoints:0.0}}, last {{receive.LastWeekFantasyPoints:0.0}}, injury {{Safe(receive.InjuryStatus)}}).
YOUR ROSTER:
{{RosterLines(s, target, tradableOnly: true)}}
PROPOSER ROSTER:
{{RosterLines(s, proposer, tradableOnly: true)}}
""";
        return await _ai.ChatJsonAsync<GemmaTradeDecision>(s, system, user, ct, 600, .58);
    }

    public static List<string> ValidateAndApplyLineup(LeagueState s, FantasyTeam team, GemmaLineupDecision decision)
    {
        var errors = new List<string>();
        var required = LeagueEngine.StarterSlots;
        var proposed = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var slot in required)
        {
            var lockedExisting = team.Roster.FirstOrDefault(r => r.Slot.Equals(slot, StringComparison.OrdinalIgnoreCase) && LeagueEngine.IsLocked(LeagueEngine.PlayerById(s, r.PlayerId)));
            if (lockedExisting != null)
            {
                proposed[slot] = lockedExisting.PlayerId;
                continue;
            }
            string? id = decision.Lineup.FirstOrDefault(kv => kv.Key.Equals(slot, StringComparison.OrdinalIgnoreCase)).Value;
            if (string.IsNullOrWhiteSpace(id)) { errors.Add($"Missing {slot}"); continue; }
            var entry = team.Roster.FirstOrDefault(r => r.PlayerId.Equals(id, StringComparison.OrdinalIgnoreCase));
            var p = LeagueEngine.PlayerById(s, id);
            if (entry == null || p == null) { errors.Add($"{slot} uses a player not on the roster: {id}"); continue; }
            if (LeagueEngine.IsLocked(p) && !entry.Slot.Equals(slot, StringComparison.OrdinalIgnoreCase)) { errors.Add($"{p.Name} is locked in {entry.Slot}"); continue; }
            if (!LeagueEngine.SlotAccepts(slot, p.Position)) { errors.Add($"{p.Name} ({p.Position}) is not eligible for {slot}"); continue; }
            proposed[slot] = id;
        }
        var dupes = proposed.Values.GroupBy(x => x, StringComparer.OrdinalIgnoreCase).Where(g => g.Count() > 1).Select(g => g.Key).ToList();
        foreach (var d in dupes) errors.Add($"Player used more than once: {LeagueEngine.PlayerName(s, d)}");
        if (errors.Count > 0) return errors;

        foreach (var r in team.Roster)
        {
            if (!LeagueEngine.IsLocked(LeagueEngine.PlayerById(s, r.PlayerId))) r.Slot = "BN";
        }
        foreach (var kv in proposed)
        {
            var r = team.Roster.First(x => x.PlayerId.Equals(kv.Value, StringComparison.OrdinalIgnoreCase));
            r.Slot = kv.Key;
        }
        return errors;
    }

    public static int QueueWaiverClaims(LeagueState s, FantasyTeam team, GemmaRosterPlanDecision plan)
    {
        int queued = 0;
        var usedAdds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var w in plan.WaiverClaims.OrderBy(w => w.Priority).Take(3))
        {
            var add = LeagueEngine.PlayerById(s, w.AddPlayerId);
            if (add == null || !add.Active || !LeagueEngine.FantasyPos(add.Position) || LeagueEngine.IsRostered(s, add.Id) || !usedAdds.Add(add.Id)) continue;
            if (!string.IsNullOrWhiteSpace(w.DropPlayerId))
            {
                var dropEntry = team.Roster.FirstOrDefault(r => r.PlayerId.Equals(w.DropPlayerId, StringComparison.OrdinalIgnoreCase));
                if (dropEntry == null || LeagueEngine.IsLocked(LeagueEngine.PlayerById(s, dropEntry.PlayerId))) continue;
            }
            else if (team.Roster.Count >= LeagueEngine.RosterSize) continue;
            int priority = Math.Max(1, w.Priority);
            s.WaiverClaims.Add(new WaiverClaim { TeamId = team.Id, AddPlayerId = add.Id, DropPlayerId = w.DropPlayerId ?? "", ClaimPriority = priority });
            queued++;
        }
        return queued;
    }

    public static bool ValidTradeProposal(LeagueState s, FantasyTeam proposer, GemmaTradeProposal proposal, out FantasyTeam? target)
    {
        target = s.Teams.FirstOrDefault(t => t.Id == proposal.ToTeamId && t.Id != proposer.Id);
        if (target == null) return false;
        var give = LeagueEngine.PlayerById(s, proposal.GivePlayerId); var receive = LeagueEngine.PlayerById(s, proposal.ReceivePlayerId);
        if (give == null || receive == null || give.Position is "K" or "DEF" || receive.Position is "K" or "DEF") return false;
        if (!proposer.Roster.Any(r => r.PlayerId.Equals(give.Id, StringComparison.OrdinalIgnoreCase))) return false;
        if (!target.Roster.Any(r => r.PlayerId.Equals(receive.Id, StringComparison.OrdinalIgnoreCase))) return false;
        return true;
    }

    private static string PlayerLines(IEnumerable<Player> players)
        => string.Join("\n", players.Select(p => $"{p.Id}|{p.Name}|{p.Position}|{p.NflTeam}|rank={p.SearchRank}|proj={p.Projection:0.0}|last={p.LastWeekFantasyPoints:0.0}|season={p.SeasonFantasyPoints:0.0}|inj={Safe(p.InjuryStatus)}|opp={Safe(p.Opponent)}|news={Trim(p.News, 90)}"));

    private static string RosterLines(LeagueState s, FantasyTeam team, bool tradableOnly = false, bool includeLock = false)
    {
        var lines = new List<string>();
        foreach (var r in team.Roster)
        {
            var p = LeagueEngine.PlayerById(s, r.PlayerId); if (p == null) continue;
            if (tradableOnly && p.Position is not ("QB" or "RB" or "WR" or "TE")) continue;
            string locked = includeLock && LeagueEngine.IsLocked(p) ? "|LOCKED" : "";
            lines.Add($"{p.Id}|{p.Name}|{p.Position}|slot={r.Slot}|NFL={p.NflTeam}|rank={p.SearchRank}|proj={p.Projection:0.0}|last={p.LastWeekFantasyPoints:0.0}|season={p.SeasonFantasyPoints:0.0}|inj={Safe(p.InjuryStatus)}|opp={Safe(p.Opponent)}|news={Trim(p.News, 100)}{locked}");
        }
        return string.Join("\n", lines);
    }

    private static string Safe(string? s) => string.IsNullOrWhiteSpace(s) ? "none" : s.Replace('\n', ' ').Replace('\r', ' ');
    private static string Trim(string? s, int max) { string v = Safe(s); return v.Length <= max ? v : v[..max]; }
}
