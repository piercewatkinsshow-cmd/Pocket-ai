namespace AIFantasyFootball;

public static class LeagueEngine
{
    public static readonly string[] StarterSlots = { "QB", "RB1", "RB2", "WR1", "WR2", "TE", "FLEX", "K", "DEF" };
    public const int RosterSize = 15;

    public static LeagueState CreateNewLeague()
    {
        var state = new LeagueState { Season = DateTime.Now.Year, CurrentWeek = 1 };
        state.Teams = new List<FantasyTeam>
        {
            NewTeam(1, true, "You", "My Team", OwnerStyle.Human, "Human commissioner. Full manual control."),
            NewTeam(2, false, "Mason 'Metrics' Cole", "Expected Value", OwnerStyle.Analytics, "Cold, numbers-first manager. Loves usage, efficiency and projections; hates narrative hype.", 48, 38),
            NewTeam(3, false, "Rico Blaze", "Fourth & Reckless", OwnerStyle.Aggressive, "Bold risk-taker. Chases ceiling, rookies and breakout games. Talks a lot of trash.", 90, 88),
            NewTeam(4, false, "Harold Stone", "Floor Is Lava", OwnerStyle.Conservative, "Veteran-minded and cautious. Prefers stable roles, healthy players and proven volume.", 35, 20),
            NewTeam(5, false, "Lexi Prime", "Name Brand FC", OwnerStyle.StarChaser, "Star-player obsessed. Values elite names and high-profile offenses, sometimes too much.", 70, 62),
            NewTeam(6, false, "Drew Chaos", "Waiver Goblins", OwnerStyle.Contrarian, "Unpredictable contrarian. Loves zigging when everyone zags and taking weird-but-defensible shots.", 78, 75)
        };
        state.UserDraftRules.Add(new DraftRule { Position = "RB", MinimumCount = 2, ByRound = 6 });
        state.Matchups = GenerateRegularSchedule();
        state.Board.Add(new BoardPost { TeamId = 2, Author = "Mason 'Metrics' Cole", Text = "Welcome to the league. Your draft board is probably already mathematically obsolete." });
        state.Board.Add(new BoardPost { TeamId = 3, Author = "Rico Blaze", Text = "Six teams. Five future victims. Let's work." });
        return state;
    }

    private static FantasyTeam NewTeam(int id, bool human, string owner, string name, OwnerStyle style, string personality, int trash = 50, int risk = 50) =>
        new()
        {
            Id = id, IsHuman = human, PreviousWaiverPriority = id,
            Owner = new OwnerProfile { Name = owner, TeamName = name, Style = style, Personality = personality, TrashTalk = trash, RiskTolerance = risk }
        };

    public static List<Matchup> GenerateRegularSchedule()
    {
        // 6 teams: 3 games/week, 5-week round robin, repeated through week 14.
        int[][] rounds =
        {
            new[]{1,6,2,5,3,4}, new[]{1,5,6,4,2,3}, new[]{1,4,5,3,6,2},
            new[]{1,3,4,2,5,6}, new[]{1,2,3,6,4,5}
        };
        var list = new List<Matchup>();
        for (int week = 1; week <= 14; week++)
        {
            var r = rounds[(week - 1) % rounds.Length];
            for (int i = 0; i < r.Length; i += 2)
            {
                bool flip = week % 2 == 0;
                list.Add(new Matchup { Week = week, HomeTeamId = flip ? r[i + 1] : r[i], AwayTeamId = flip ? r[i] : r[i + 1], Stage = "Regular Season" });
            }
        }
        return list;
    }

    public static IReadOnlyList<FantasyTeam> Standings(LeagueState s) => s.Teams
        .OrderByDescending(t => t.Wins)
        .ThenBy(t => t.Losses)
        .ThenByDescending(t => t.PointsFor)
        .ToList();

    public static IReadOnlyList<FantasyTeam> WaiverOrder(LeagueState s) => s.Teams
        .OrderByDescending(t => t.Losses)
        .ThenBy(t => t.PointsFor)
        .ThenBy(t => t.PreviousWaiverPriority)
        .ToList();

    public static string GetPlayerName(LeagueState s, string id) => s.Players.TryGetValue(id, out var p) ? p.Name : id;
    public static Player? GetPlayer(LeagueState s, string id) => s.Players.TryGetValue(id, out var p) ? p : null;
    public static FantasyTeam HumanTeam(LeagueState s) => s.Teams.First(t => t.IsHuman);
    public static bool IsRostered(LeagueState s, string playerId) => s.Teams.Any(t => t.Roster.Any(r => r.PlayerId == playerId));

    public static IEnumerable<Player> FreeAgents(LeagueState s, string? position = null) => s.Players.Values
        .Where(p => p.Active && !IsRostered(s, p.Id) && IsFantasyPosition(p.Position) && (position == null || p.Position == position))
        .OrderBy(p => p.SearchRank)
        .ThenByDescending(p => p.SeasonFantasyPoints);

    public static bool IsFantasyPosition(string pos) => pos is "QB" or "RB" or "WR" or "TE" or "K" or "DEF";

    public static void RunAutoDraft(LeagueState s)
    {
        if (s.Players.Count == 0) throw new InvalidOperationException("Download player data first using Prepare Upcoming Week.");
        if (s.DraftComplete) throw new InvalidOperationException("The draft is already complete.");
        foreach (var t in s.Teams) t.Roster.Clear();
        s.DraftPicks.Clear();

        int pickNo = 0;
        for (int round = 1; round <= RosterSize; round++)
        {
            IEnumerable<FantasyTeam> order = round % 2 == 1 ? s.Teams.OrderBy(t => t.Id) : s.Teams.OrderByDescending(t => t.Id);
            foreach (var team in order)
            {
                pickNo++;
                Player? pick = team.IsHuman ? PickForHuman(s, team, round) : PickForAi(s, team, round);
                if (pick == null) continue;
                team.Roster.Add(new RosterEntry { PlayerId = pick.Id, Slot = "BN" });
                s.DraftPicks.Add(new DraftPick { PickNumber = pickNo, Round = round, TeamId = team.Id, PlayerId = pick.Id });
            }
        }
        foreach (var t in s.Teams) AutoSetLineup(s, t);
        s.DraftComplete = true;
    }

    private static Player? PickForHuman(LeagueState s, FantasyTeam team, int round)
    {
        var availablePriorities = s.UserDraftPriority
            .Where(id => s.Players.ContainsKey(id) && !IsRostered(s, id))
            .Select(id => s.Players[id])
            .ToList();
        foreach (var rule in s.UserDraftRules.Where(r => round <= r.ByRound))
        {
            int have = team.Roster.Count(r => GetPlayer(s, r.PlayerId)?.Position == rule.Position);
            int picksLeftThroughDeadline = rule.ByRound - round + 1;
            if (have < rule.MinimumCount && rule.MinimumCount - have >= picksLeftThroughDeadline)
            {
                var forced = availablePriorities.FirstOrDefault(p => p.Position == rule.Position)
                    ?? BestAvailable(s, team, rule.Position, OwnerStyle.Human, round);
                if (forced != null) return forced;
            }
        }
        return availablePriorities.FirstOrDefault() ?? BestAvailable(s, team, null, OwnerStyle.Human, round);
    }

    private static Player? PickForAi(LeagueState s, FantasyTeam team, int round)
    {
        string? forcedPos = NeededPosition(s, team, round);
        return BestAvailable(s, team, forcedPos, team.Owner.Style, round);
    }

    private static string? NeededPosition(LeagueState s, FantasyTeam team, int round)
    {
        int Count(string p) => team.Roster.Count(r => GetPlayer(s, r.PlayerId)?.Position == p);
        if (round >= 6 && Count("RB") < 2) return "RB";
        if (round >= 6 && Count("WR") < 2) return "WR";
        if (round >= 9 && Count("QB") < 1) return "QB";
        if (round >= 10 && Count("TE") < 1) return "TE";
        if (round >= 14 && Count("DEF") < 1) return "DEF";
        if (round >= 15 && Count("K") < 1) return "K";
        return null;
    }

    private static Player? BestAvailable(LeagueState s, FantasyTeam team, string? position, OwnerStyle style, int round)
    {
        var candidates = s.Players.Values.Where(p => p.Active && IsFantasyPosition(p.Position) && !IsRostered(s, p.Id));
        if (position != null) candidates = candidates.Where(p => p.Position == position);
        return candidates
            .Select(p => new { P = p, Score = DraftScore(s, team, p, style, round) })
            .OrderByDescending(x => x.Score).FirstOrDefault()?.P;
    }

    private static double DraftScore(LeagueState s, FantasyTeam team, Player p, OwnerStyle style, int round)
    {
        double baseScore = 12000 - Math.Min(11000, p.SearchRank * 8);
        baseScore += p.SeasonFantasyPoints * 3 + p.LastWeekFantasyPoints;
        int atPos = team.Roster.Count(r => GetPlayer(s, r.PlayerId)?.Position == p.Position);
        double need = p.Position switch
        {
            "RB" => atPos < 2 ? 900 : atPos < 4 ? 250 : -250,
            "WR" => atPos < 2 ? 900 : atPos < 4 ? 250 : -250,
            "QB" => atPos == 0 ? 550 : -900,
            "TE" => atPos == 0 ? 450 : -550,
            "K" => round < 13 ? -1800 : atPos == 0 ? 250 : -1000,
            "DEF" => round < 12 ? -1600 : atPos == 0 ? 300 : -1000,
            _ => 0
        };
        double styleAdj = style switch
        {
            OwnerStyle.Analytics => p.SeasonFantasyPoints * 1.2 + (p.InjuryStatus.Length == 0 ? 120 : -150),
            OwnerStyle.Aggressive => p.LastWeekFantasyPoints * 2.0 + (p.SearchRank > 150 ? 80 : 0),
            OwnerStyle.Conservative => (p.InjuryStatus.Length == 0 ? 250 : -450) - Math.Max(0, p.SearchRank - 250) * .2,
            OwnerStyle.StarChaser => Math.Max(0, 500 - p.SearchRank) * .8,
            OwnerStyle.Contrarian => ((p.Id.GetHashCode() & 0x7fffffff) % 350) - 100,
            _ => 0
        };
        return baseScore + need + styleAdj;
    }

    public static void AutoSetLineup(LeagueState s, FantasyTeam team)
    {
        foreach (var r in team.Roster) r.Slot = "BN";
        AssignBest(s, team, "QB", "QB", 1);
        AssignBest(s, team, "RB", "RB1", 1); AssignBest(s, team, "RB", "RB2", 1);
        AssignBest(s, team, "WR", "WR1", 1); AssignBest(s, team, "WR", "WR2", 1);
        AssignBest(s, team, "TE", "TE", 1);
        AssignFlex(s, team);
        AssignBest(s, team, "K", "K", 1);
        AssignBest(s, team, "DEF", "DEF", 1);
    }

    private static void AssignBest(LeagueState s, FantasyTeam t, string pos, string slot, int count)
    {
        var pick = t.Roster.Where(r => r.Slot == "BN" && GetPlayer(s, r.PlayerId)?.Position == pos)
            .OrderByDescending(r => PlayerStartScore(GetPlayer(s, r.PlayerId))).FirstOrDefault();
        if (pick != null) pick.Slot = slot;
    }

    private static void AssignFlex(LeagueState s, FantasyTeam t)
    {
        var pick = t.Roster.Where(r => r.Slot == "BN" && GetPlayer(s, r.PlayerId)?.Position is "RB" or "WR" or "TE")
            .OrderByDescending(r => PlayerStartScore(GetPlayer(s, r.PlayerId))).FirstOrDefault();
        if (pick != null) pick.Slot = "FLEX";
    }

    private static double PlayerStartScore(Player? p)
    {
        if (p == null) return -9999;
        double injury = p.InjuryStatus.ToUpperInvariant() switch { "OUT" or "IR" => -5000, "DOUBTFUL" => -1800, "QUESTIONABLE" => -250, _ => 0 };
        return p.Projection * 5 + p.LastWeekFantasyPoints * 2 + p.SeasonFantasyPoints * .2 + (1200 - Math.Min(1200, p.SearchRank)) + injury;
    }

    public static List<string> RecommendHumanLineup(LeagueState s)
    {
        var human = HumanTeam(s);
        var current = human.Roster.ToDictionary(r => r.PlayerId, r => r.Slot);
        var clone = new FantasyTeam { Roster = human.Roster.Select(r => new RosterEntry { PlayerId = r.PlayerId, Slot = r.Slot }).ToList() };
        AutoSetLineup(s, clone);
        var recs = new List<string>();
        foreach (var r in clone.Roster.Where(r => r.Slot != "BN"))
        {
            if (!current.TryGetValue(r.PlayerId, out var old) || old != r.Slot)
                recs.Add($"Consider starting {GetPlayerName(s, r.PlayerId)} at {r.Slot}.");
        }
        foreach (var r in human.Roster)
        {
            var p = GetPlayer(s, r.PlayerId);
            if (p != null && !string.IsNullOrWhiteSpace(p.InjuryStatus)) recs.Add($"{p.Name}: injury status {p.InjuryStatus}. Review before kickoff.");
        }
        if (recs.Count == 0) recs.Add("Your current starters already match the app's best available lineup estimate.");
        return recs.Distinct().ToList();
    }

    public static List<string> GenerateAiWaiverClaims(LeagueState s)
    {
        var notes = new List<string>();
        foreach (var team in s.Teams.Where(t => !t.IsHuman))
        {
            var rosterPlayers = team.Roster.Select(r => GetPlayer(s, r.PlayerId)).Where(p => p != null).Cast<Player>().ToList();
            if (rosterPlayers.Count == 0) continue;
            var worst = rosterPlayers.OrderBy(LocalRosterValue).First();
            var best = FreeAgents(s).Take(120).OrderByDescending(LocalRosterValue).FirstOrDefault();
            if (best == null) continue;
            double threshold = team.Owner.Style switch
            {
                OwnerStyle.Aggressive => 1.08, OwnerStyle.Contrarian => 1.10, OwnerStyle.Analytics => 1.14,
                OwnerStyle.StarChaser => 1.16, OwnerStyle.Conservative => 1.22, _ => 1.15
            };
            bool urgent = worst.InjuryStatus.Equals("OUT", StringComparison.OrdinalIgnoreCase) || worst.InjuryStatus.Equals("IR", StringComparison.OrdinalIgnoreCase);
            if (urgent || LocalRosterValue(best) > LocalRosterValue(worst) * threshold)
            {
                s.WaiverClaims.RemoveAll(c => c.TeamId == team.Id && c.AddPlayerId == best.Id);
                s.WaiverClaims.Add(new WaiverClaim { TeamId = team.Id, AddPlayerId = best.Id, DropPlayerId = worst.Id });
                notes.Add($"{team.Owner.TeamName} claimed {best.Name}, dropping {worst.Name}.");
            }
        }
        return notes;
    }

    private static double LocalRosterValue(Player p)
    {
        double injury = p.InjuryStatus.ToUpperInvariant() switch { "OUT" or "IR" => -1000, "DOUBTFUL" => -400, "QUESTIONABLE" => -100, _ => 0 };
        return p.Projection * 20 + p.SeasonFantasyPoints * 3 + p.LastWeekFantasyPoints * 4 + Math.Max(0, 1200 - p.SearchRank) + injury;
    }

    public static void ProcessWaivers(LeagueState s)
    {
        var order = WaiverOrder(s).Select((t, i) => new { t.Id, Rank = i }).ToDictionary(x => x.Id, x => x.Rank);
        var claims = s.WaiverClaims.OrderBy(c => order.GetValueOrDefault(c.TeamId, 999)).ThenBy(c => c.SubmittedAt).ToList();
        foreach (var claim in claims)
        {
            if (IsRostered(s, claim.AddPlayerId)) continue;
            var team = s.Teams.FirstOrDefault(t => t.Id == claim.TeamId);
            if (team == null) continue;
            if (!string.IsNullOrWhiteSpace(claim.DropPlayerId)) team.Roster.RemoveAll(r => r.PlayerId == claim.DropPlayerId);
            if (team.Roster.Count >= RosterSize) continue;
            team.Roster.Add(new RosterEntry { PlayerId = claim.AddPlayerId, Slot = "BN" });
        }
        var newOrder = WaiverOrder(s).ToList();
        for (int i = 0; i < newOrder.Count; i++) newOrder[i].PreviousWaiverPriority = i + 1;
        s.WaiverClaims.Clear();
    }

    public static bool ExecuteTrade(LeagueState s, TradeOffer trade)
    {
        var from = s.Teams.First(t => t.Id == trade.FromTeamId);
        var to = s.Teams.First(t => t.Id == trade.ToTeamId);
        var give = from.Roster.FirstOrDefault(r => r.PlayerId == trade.GivePlayerId);
        var receive = to.Roster.FirstOrDefault(r => r.PlayerId == trade.ReceivePlayerId);
        if (give == null || receive == null) return false;
        from.Roster.Remove(give); to.Roster.Remove(receive);
        from.Roster.Add(new RosterEntry { PlayerId = receive.PlayerId, Slot = "BN" });
        to.Roster.Add(new RosterEntry { PlayerId = give.PlayerId, Slot = "BN" });
        AutoSetLineup(s, to);
        return true;
    }

    public static double TeamWeekScore(LeagueState s, FantasyTeam team, int week)
    {
        double sum = 0;
        foreach (var r in team.Roster.Where(r => r.Slot != "BN"))
        {
            string key = $"{week}:{r.PlayerId}";
            if (s.WeeklyPlayerPoints.TryGetValue(key, out var p)) sum += p;
        }
        return Math.Round(sum, 2);
    }

    public static void FinalizeWeek(LeagueState s, int week)
    {
        foreach (var m in s.Matchups.Where(m => m.Week == week && !m.Final))
        {
            var home = s.Teams.First(t => t.Id == m.HomeTeamId);
            var away = s.Teams.First(t => t.Id == m.AwayTeamId);
            m.HomePoints = TeamWeekScore(s, home, week);
            m.AwayPoints = TeamWeekScore(s, away, week);
            m.Final = true;
            home.PointsFor += m.HomePoints; home.PointsAgainst += m.AwayPoints;
            away.PointsFor += m.AwayPoints; away.PointsAgainst += m.HomePoints;
            if (m.HomePoints > m.AwayPoints) { home.Wins++; away.Losses++; }
            else if (m.AwayPoints > m.HomePoints) { away.Wins++; home.Losses++; }
            else { home.Ties++; away.Ties++; }
        }
        s.LastScoredWeek = Math.Max(s.LastScoredWeek, week);
        if (week == 14) SeedPlayoffs(s);
    }

    private static void SeedPlayoffs(LeagueState s)
    {
        if (s.Matchups.Any(m => m.Week >= 15)) return;
        var seeds = Standings(s).Take(4).ToList();
        // Two-week semifinals: 1v4 and 2v3. Two-week championship is reseeded after week 16.
        for (int w = 15; w <= 16; w++)
        {
            s.Matchups.Add(new Matchup { Week = w, HomeTeamId = seeds[0].Id, AwayTeamId = seeds[3].Id, Stage = "Semifinal" });
            s.Matchups.Add(new Matchup { Week = w, HomeTeamId = seeds[1].Id, AwayTeamId = seeds[2].Id, Stage = "Semifinal" });
        }
    }

    public static void SeedChampionshipAfterWeek16(LeagueState s)
    {
        if (s.Matchups.Any(m => m.Week >= 17)) return;
        var semis = s.Matchups.Where(m => m.Stage == "Semifinal" && m.Week is 15 or 16).ToList();
        if (semis.Count < 4 || semis.Any(m => !m.Final)) return;
        int Winner(int a, int b)
        {
            double aPts = semis.Where(m => m.HomeTeamId == a || m.AwayTeamId == a).Sum(m => m.HomeTeamId == a ? m.HomePoints : m.AwayPoints);
            double bPts = semis.Where(m => m.HomeTeamId == b || m.AwayTeamId == b).Sum(m => m.HomeTeamId == b ? m.HomePoints : m.AwayPoints);
            return aPts >= bPts ? a : b;
        }
        var pair1 = semis.First(m => m.Week == 15);
        var pair2 = semis.Where(m => m.Week == 15).Skip(1).First();
        int w1 = Winner(pair1.HomeTeamId, pair1.AwayTeamId), w2 = Winner(pair2.HomeTeamId, pair2.AwayTeamId);
        int l1 = w1 == pair1.HomeTeamId ? pair1.AwayTeamId : pair1.HomeTeamId;
        int l2 = w2 == pair2.HomeTeamId ? pair2.AwayTeamId : pair2.HomeTeamId;
        for (int w = 17; w <= 18; w++)
        {
            s.Matchups.Add(new Matchup { Week = w, HomeTeamId = w1, AwayTeamId = w2, Stage = "Championship" });
            s.Matchups.Add(new Matchup { Week = w, HomeTeamId = l1, AwayTeamId = l2, Stage = "Third Place" });
        }
    }

    public static bool SetHumanSlot(LeagueState s, string playerId, string targetSlot, out string error)
    {
        error = "";
        var team = HumanTeam(s);
        var entry = team.Roster.FirstOrDefault(r => r.PlayerId == playerId);
        var player = GetPlayer(s, playerId);
        if (entry == null || player == null) { error = "Player is not on your roster."; return false; }
        if (targetSlot != "BN" && !SlotAccepts(targetSlot, player.Position)) { error = $"{player.Position} is not eligible for {targetSlot}."; return false; }
        var occupied = team.Roster.FirstOrDefault(r => r.Slot == targetSlot && targetSlot != "BN");
        if (occupied != null) occupied.Slot = "BN";
        entry.Slot = targetSlot;
        return true;
    }

    public static bool SlotAccepts(string slot, string pos) => slot switch
    {
        "QB" => pos == "QB", "RB1" or "RB2" => pos == "RB", "WR1" or "WR2" => pos == "WR",
        "TE" => pos == "TE", "FLEX" => pos is "RB" or "WR" or "TE", "K" => pos == "K", "DEF" => pos == "DEF", "BN" => true, _ => false
    };
}
