-- MordhauCombat_HUD.lua
-- Authentic Mordhau v0.2 HUD:
-- 1. Uses a 0x0 non-consuming overlay panel (setConsumeMouseEvents(false)) so it NEVER blocks LMB/RMB clicks on inventory, looting, or health UI!
-- 2. Uses getText("UI_Mordhau_...") from B42 UTF-8 UI.json for 100% clean Russian & English localization.
-- 3. Draws smooth continuous 360/240-degree directional arrow around the center dot (zero 90-degree snapping!),
--    crisp inward spearhead indicator for Thrust, 4-tick diagonal X hitmarker, damage/kill popups,
--    bottom-center Stamina | Health bar, Enemy target bar, and dynamic keybind hints.

require "ISUI/ISPanel"

MordhauHUD = ISPanel:derive("MordhauHUD")
MordhauHUD.instance = nil
MordhauHUD.enabled = true
MordhauHUD.showKeyHints = true
MordhauHUD.displayAngle = 0.0

local function tr(key, fallback)
    if getText then
        local res = getText(key)
        if res and res ~= key then
            return res
        end
    end
    return fallback or key
end

local CALLOUT_KEYS = {
    ["parry"] = "UI_Mordhau_Callout_Parry",
    ["riposte"] = "UI_Mordhau_Callout_Riposte",
    ["chamber"] = "UI_Mordhau_Callout_Chamber",
    ["feint"] = "UI_Mordhau_Callout_Feint",
    ["morph"] = "UI_Mordhau_Callout_Morph",
    ["accel"] = "UI_Mordhau_Callout_Accel",
    ["drag"] = "UI_Mordhau_Callout_Drag",
    ["kick"] = "UI_Mordhau_Callout_Kick",
    ["bounce"] = "UI_Mordhau_Callout_Bounce",
    ["nostamina"] = "UI_Mordhau_Callout_NoStamina",
    ["grip_halfsword"] = "UI_Mordhau_Callout_GripHalfSword",
    ["grip_spike"] = "UI_Mordhau_Callout_GripSpike",
    ["grip_normal"] = "UI_Mordhau_Callout_GripNormal",
}

local function wrapAngle(a)
    while a > math.pi do a = a - math.pi * 2.0 end
    while a < -math.pi do a = a + math.pi * 2.0 end
    return a
end

local function getCombatState(player)
    local md = player and player:getModData() or nil

    local meleeActive = false
    if _G.Mordhau_isMeleeModeActive then
        meleeActive = _G.Mordhau_isMeleeModeActive()
    elseif md and md.mordhau_melee_active ~= nil then
        meleeActive = md.mordhau_melee_active
    else
        local item = player and player:getPrimaryHandItem() or nil
        if item and instanceof(item, "HandWeapon") and not item:isRanged() and not item:isBareHands() then
            meleeActive = true
        end
    end

    local feedbackRaw = (_G.Mordhau_getLastCombatFeedback and _G.Mordhau_getLastCombatFeedback()) or (md and md.mordhau_feedback) or ""
    local feedbackAlpha = (_G.Mordhau_getLastCombatFeedbackAlpha and _G.Mordhau_getLastCombatFeedbackAlpha()) or (md and md.mordhau_feedback_alpha) or 0.0
    local phase = (_G.Mordhau_getPhase and _G.Mordhau_getPhase()) or (md and md.mordhau_phase) or "idle"
    local stamina = (_G.Mordhau_getStamina and _G.Mordhau_getStamina()) or (md and md.mordhau_stamina) or 100.0
    local maxStamina = (_G.Mordhau_getMaxStamina and _G.Mordhau_getMaxStamina()) or (md and md.mordhau_max_stamina) or 100.0

    -- If unarmed and no active kick / stamina recovery / callout, hide HUD
    if not meleeActive and phase ~= "kick" and stamina >= (maxStamina - 0.5) and (feedbackRaw == "" or feedbackAlpha <= 0.01) then
        return nil
    end

    local selectedDir = (_G.Mordhau_getDirection and _G.Mordhau_getDirection()) or (md and md.mordhau_dir) or "right"
    local activeDir = (_G.Mordhau_getActiveDirection and _G.Mordhau_getActiveDirection()) or (md and md.mordhau_active_dir) or selectedDir

    local selectedAngle = (_G.Mordhau_getSelectedAngleRad and _G.Mordhau_getSelectedAngleRad()) or (md and md.mordhau_angle_rad) or 0.0
    local activeAngle = (_G.Mordhau_getActiveAngleRad and _G.Mordhau_getActiveAngleRad()) or (md and md.mordhau_active_angle_rad) or selectedAngle

    local progress = (_G.Mordhau_getPhaseProgress and _G.Mordhau_getPhaseProgress()) or (md and md.mordhau_progress) or 0.0
    local riposte = (_G.Mordhau_isRiposteAvailable and _G.Mordhau_isRiposteAvailable()) or (md and md.mordhau_riposte) or false
    local accelDrag = (md and md.mordhau_accel_drag) or 1.0
    local altGrip = (_G.Mordhau_isAltGripMode and _G.Mordhau_isAltGripMode()) or (md and md.mordhau_alt_grip) or false
    local hitAlpha = (md and md.mordhau_hitmarker_alpha) or 0.0
    local hitKill = (md and md.mordhau_hitmarker_kill) or false
    local dmgPoints = (md and md.mordhau_dmg_points) or 0
    local dmgAlpha = (md and md.mordhau_dmg_alpha) or 0.0
    local dmgKill = (md and md.mordhau_dmg_kill) or false
    local targetName = (md and md.mordhau_target_name) or ""
    local targetHp = (md and md.mordhau_target_hp) or 0.0

    local feedback = feedbackRaw
    if CALLOUT_KEYS[feedbackRaw] then
        feedback = tr(CALLOUT_KEYS[feedbackRaw], feedbackRaw)
    end

    if targetName == "zombie" then
        targetName = tr("UI_Mordhau_Zombie", "Zombie")
    elseif targetName == "survivor" then
        targetName = tr("UI_Mordhau_Survivor", "Survivor")
    end

    local playerHp = 100
    if player and player:getBodyDamage() then
        playerHp = math.floor(math.max(0, math.min(100, player:getBodyDamage():getOverallBodyHealth())))
    end

    return {
        meleeActive = meleeActive,
        selectedDir = selectedDir,
        activeDir = activeDir,
        selectedAngle = selectedAngle,
        activeAngle = activeAngle,
        phase = phase,
        progress = progress,
        stamina = stamina,
        maxStamina = maxStamina,
        playerHp = playerHp,
        riposte = riposte,
        feedback = feedback,
        feedbackAlpha = feedbackAlpha,
        accelDrag = accelDrag,
        altGrip = altGrip,
        hitAlpha = hitAlpha,
        hitKill = hitKill,
        dmgPoints = dmgPoints,
        dmgAlpha = dmgAlpha,
        dmgKill = dmgKill,
        targetName = targetName,
        targetHp = targetHp
    }
end

function MordhauHUD:new()
    local o = ISPanel:new(0, 0, 0, 0)
    setmetatable(o, self)
    self.__index = self
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0 }
    o.borderColor = { r = 0, g = 0, b = 0, a = 0 }
    o.moveWithMouse = false
    o.anchorLeft = false
    o.anchorRight = false
    o.anchorTop = false
    o.anchorBottom = false
    return o
end

function MordhauHUD:initialise()
    ISPanel.initialise(self)
end

function MordhauHUD:instantiate()
    ISPanel.instantiate(self)
    if self.javaObject then
        self.javaObject:setConsumeMouseEvents(false)
        self.javaObject:setWidth(0)
        self.javaObject:setHeight(0)
    end
end

function MordhauHUD:drawLine(x1, y1, x2, y2, thickness, a, r, g, b)
    local dx = x2 - x1
    local dy = y2 - y1
    local dist = math.sqrt(dx * dx + dy * dy)
    local steps = math.max(1, math.floor(dist / 1.5))
    local half = thickness * 0.5
    for i = 0, steps do
        local t = i / steps
        local px = x1 + dx * t
        local py = y1 + dy * t
        self:drawRect(px - half, py - half, thickness, thickness, a, r, g, b)
    end
end

function MordhauHUD:drawCircleRing(cx, cy, radius, thickness, a, r, g, b, segments)
    local segs = segments or 36
    local step = (math.pi * 2.0) / segs
    for i = 0, segs - 1 do
        local a1 = i * step
        local a2 = (i + 1) * step
        local x1 = cx + math.cos(a1) * radius
        local y1 = cy + math.sin(a1) * radius
        local x2 = cx + math.cos(a2) * radius
        local y2 = cy + math.sin(a2) * radius
        self:drawLine(x1, y1, x2, y2, thickness, a, r, g, b)
    end
end

-- Draws Mordhau directional chevron/arrow pointing INWARD from the chosen swing side toward the center dot
-- (or pointing outward along the chosen sector cleanly around the center dot)
function MordhauHUD:drawDirectionalArrow(cx, cy, dirX, dirY, dist, size, thickness, a, r, g, b)
    local tipX = cx + dirX * (dist + size * 0.55)
    local tipY = cy + dirY * (dist + size * 0.55)
    local baseX = cx + dirX * (dist - size * 0.45)
    local baseY = cy + dirY * (dist - size * 0.45)
    local perpX = -dirY
    local perpY = dirX
    local wingBaseX = cx + dirX * (dist + size * 0.05)
    local wingBaseY = cy + dirY * (dist + size * 0.05)
    local wingSpan = size * 0.48
    local leftX = wingBaseX + perpX * wingSpan
    local leftY = wingBaseY + perpY * wingSpan
    local rightX = wingBaseX - perpX * wingSpan
    local rightY = wingBaseY - perpY * wingSpan

    self:drawLine(baseX, baseY, tipX, tipY, thickness + 2, a * 0.75, 0.04, 0.04, 0.04)
    self:drawLine(tipX, tipY, leftX, leftY, thickness + 2, a * 0.75, 0.04, 0.04, 0.04)
    self:drawLine(tipX, tipY, rightX, rightY, thickness + 2, a * 0.75, 0.04, 0.04, 0.04)

    self:drawLine(baseX, baseY, tipX, tipY, thickness, a, r, g, b)
    self:drawLine(tipX, tipY, leftX, leftY, thickness, a, r, g, b)
    self:drawLine(tipX, tipY, rightX, rightY, thickness, a, r, g, b)
end

function MordhauHUD:drawThrustReticle(cx, cy, a, r, g, b)
    -- Distinct Mordhau Thrust spearhead centered right above the dot so it's unmistakable vs Overhead!
    local tipY = cy - 7
    local baseY = cy - 22
    self:drawLine(cx, baseY, cx, tipY, 4.6, a * 0.75, 0.04, 0.04, 0.04)
    self:drawLine(cx - 6, baseY + 6, cx, tipY, 4.4, a * 0.75, 0.04, 0.04, 0.04)
    self:drawLine(cx + 6, baseY + 6, cx, tipY, 4.4, a * 0.75, 0.04, 0.04, 0.04)

    self:drawLine(cx, baseY, cx, tipY, 2.6, a, r, g, b)
    self:drawLine(cx - 6, baseY + 6, cx, tipY, 2.6, a, r, g, b)
    self:drawLine(cx + 6, baseY + 6, cx, tipY, 2.6, a, r, g, b)
end

function MordhauHUD:prerender()
end

function MordhauHUD:render()
    if not MordhauHUD.enabled then
        return
    end

    if self.width ~= 0 or self.height ~= 0 then
        self:setWidth(0)
        self:setHeight(0)
    end

    local player = getSpecificPlayer(0)
    if not player or player:isDead() then
        return
    end

    local state = getCombatState(player)
    if not state then
        return
    end

    local core = getCore()
    local sw = core:getScreenWidth()
    local sh = core:getScreenHeight()
    local cx = math.floor(sw * 0.5)
    local cy = math.floor(sh * 0.5)

    -- Center-Below Callout (also works for Unarmed Kick)
    if state.feedback and state.feedback ~= "" and state.feedbackAlpha > 0.01 then
        local fbY = cy + 36
        self:drawTextCentre(state.feedback, cx + 1, fbY + 1, 0.0, 0.0, 0.0, state.feedbackAlpha * 0.85, UIFont.Medium)
        self:drawTextCentre(state.feedback, cx, fbY, 0.96, 0.96, 0.96, state.feedbackAlpha, UIFont.Medium)
    end

    local phase = state.phase
    local inActiveStrike = (phase == "windup" or phase == "release")
    local shownDir = inActiveStrike and state.activeDir or state.selectedDir

    if state.meleeActive then
        local actR, actG, actB = 0.98, 0.88, 0.42
        if phase == "windup" then
            actR, actG, actB = 1.0, 0.64, 0.18
        elseif phase == "release" then
            actR, actG, actB = 1.0, 0.25, 0.22
        elseif phase == "parry" then
            actR, actG, actB = 0.35, 0.88, 1.0
        elseif phase == "bounce" then
            actR, actG, actB = 1.0, 0.42, 0.20
        elseif phase == "recovery" or phase == "parry_recovery" then
            actR, actG, actB = 0.68, 0.68, 0.74
        end

        if state.riposte then
            actR, actG, actB = 0.35, 1.0, 0.55
        end

        -- 1. Center Dot & Smooth Continuous 360-Degree Mordhau Arrow (NO 90-degree snapping!)
        local circleRadius = 26
        self:drawCircleRing(cx, cy, circleRadius, 2.5, 0.24, 0.04, 0.04, 0.05, 36)
        self:drawCircleRing(cx, cy, circleRadius, 1.2, 0.20, 0.82, 0.84, 0.88, 36)

        self:drawRect(cx - 3, cy - 3, 6, 6, 0.78, 0.04, 0.04, 0.04)
        self:drawRect(cx - 2, cy - 2, 4, 4, 0.96, 0.98, 0.98, 0.98)

        if inActiveStrike and shownDir == "thrust" then
            self:drawThrustReticle(cx, cy, 0.98, actR, actG, actB)
        else
            local targetAng = inActiveStrike and state.activeAngle or state.selectedAngle
            local diff = wrapAngle(targetAng - (MordhauHUD.displayAngle or targetAng))
            MordhauHUD.displayAngle = wrapAngle((MordhauHUD.displayAngle or targetAng) + diff * 0.55)
            local dirX = math.cos(MordhauHUD.displayAngle)
            local dirY = math.sin(MordhauHUD.displayAngle)
            self:drawDirectionalArrow(cx, cy, dirX, dirY, 16, 13, 2.8, 0.96, actR, actG, actB)
        end
    end

    -- 2. Mordhau 4-Tick Diagonal Hitmarker
    if state.hitAlpha and state.hitAlpha > 0.01 then
        local hmR, hmG, hmB = 0.96, 0.96, 0.96
        if state.hitKill then
            hmR, hmG, hmB = 1.0, 0.32, 0.25
        end
        local inner = 8
        local outer = 17
        self:drawLine(cx - outer, cy - outer, cx - inner, cy - inner, 2.5, state.hitAlpha, hmR, hmG, hmB)
        self:drawLine(cx + inner, cy - inner, cx + outer, cy - outer, 2.5, state.hitAlpha, hmR, hmG, hmB)
        self:drawLine(cx - outer, cy + outer, cx - inner, cy + inner, 2.5, state.hitAlpha, hmR, hmG, hmB)
        self:drawLine(cx + inner, cy + inner, cx + outer, cy + outer, 2.5, state.hitAlpha, hmR, hmG, hmB)
    end

    -- 3. Floating Damage & Kill Popup ("+71 Damage", "+100 Kill")
    if state.dmgAlpha and state.dmgAlpha > 0.01 and state.dmgPoints > 0 then
        local dx = cx - 78
        local dy = cy + 44
        local totalPts = state.dmgKill and (state.dmgPoints + 100) or state.dmgPoints
        local mainStr = string.format("+%d", totalPts)
        local subStr1 = string.format("+%d   %s", state.dmgPoints, tr("UI_Mordhau_Damage", "Damage"))

        self:drawText(mainStr, dx + 1, dy + 1, 0.0, 0.0, 0.0, state.dmgAlpha * 0.85, UIFont.Medium)
        self:drawText(mainStr, dx, dy, 1.0, 0.84, 0.28, state.dmgAlpha, UIFont.Medium)

        self:drawText(subStr1, dx + 1, dy + 21, 0.0, 0.0, 0.0, state.dmgAlpha * 0.80, UIFont.Small)
        self:drawText(subStr1, dx, dy + 20, 0.92, 0.90, 0.82, state.dmgAlpha, UIFont.Small)

        if state.dmgKill then
            local subStr2 = "+100 " .. tr("UI_Mordhau_Kill", "Kill")
            self:drawText(subStr2, dx + 1, dy + 36, 0.0, 0.0, 0.0, state.dmgAlpha * 0.80, UIFont.Small)
            self:drawText(subStr2, dx, dy + 35, 1.0, 0.78, 0.25, state.dmgAlpha, UIFont.Small)
        end
    end

    -- 4. Bottom-Center Mordhau Dual Bar: Yellow Stamina (Left) | Red Health (Right) + Enemy Target Bar
    local bottomY = sh - 38
    local halfBarW = 165
    local barH = 10
    local centerGap = 32

    if state.targetName and state.targetName ~= "" and state.targetHp > 0.0 then
        local eBarW = 140
        local eBarH = 5
        local eBarX = cx - math.floor(eBarW * 0.5)
        local eBarY = bottomY - 22

        self:drawTextCentre(tr("UI_Mordhau_Enemy", "ENEMY"), cx, eBarY - 32, 0.78, 0.78, 0.78, 0.85, UIFont.Small)
        self:drawTextCentre(state.targetName, cx, eBarY - 18, 0.96, 0.96, 0.96, 0.95, UIFont.Medium)

        self:drawRect(eBarX - 1, eBarY - 1, eBarW + 2, eBarH + 2, 0.75, 0.05, 0.05, 0.05)
        local eFillW = math.floor(eBarW * math.max(0.0, math.min(1.0, state.targetHp)))
        self:drawRect(eBarX, eBarY, eFillW, eBarH, 0.90, 0.78, 0.14, 0.12)
    end

    local stamRatio = math.max(0.0, math.min(1.0, state.stamina / math.max(1.0, state.maxStamina)))
    local leftBarX = cx - centerGap - halfBarW
    self:drawRect(leftBarX - 2, bottomY - 2, halfBarW + 4, barH + 4, 0.78, 0.06, 0.06, 0.06)
    local stamFillW = math.floor(halfBarW * stamRatio)
    self:drawRect(leftBarX + (halfBarW - stamFillW), bottomY, stamFillW, barH, 0.92, 0.86, 0.64, 0.12)

    local hpRatio = math.max(0.0, math.min(1.0, state.playerHp / 100.0))
    local rightBarX = cx + centerGap
    self:drawRect(rightBarX - 2, bottomY - 2, halfBarW + 4, barH + 4, 0.78, 0.06, 0.06, 0.06)
    local hpFillW = math.floor(halfBarW * hpRatio)
    self:drawRect(rightBarX, bottomY, hpFillW, barH, 0.92, 0.76, 0.12, 0.10)

    local numStr = string.format("%d | %d", math.floor(state.stamina), state.playerHp)
    self:drawTextCentre(numStr, cx + 1, bottomY - 2, 0.0, 0.0, 0.0, 0.90, UIFont.Small)
    self:drawTextCentre(numStr, cx, bottomY - 3, 0.92, 0.90, 0.85, 0.95, UIFont.Small)

    if state.altGrip then
        local gripLabel = tr("UI_Mordhau_AltGrip_Active", "Mode: Alt-Grip [R]")
        self:drawText(gripLabel, rightBarX + halfBarW + 14, bottomY - 3, 0.95, 0.82, 0.30, 0.90, UIFont.Small)
    end

    -- 5. Left-Side Mordhau Control Hints (with live rebound key names!)
    if MordhauHUD.showKeyHints then
        local hx = 22
        local hy = math.floor(sh * 0.40)
        local lineH = 22
        local kickKeyName = "F"
        local gripKeyName = "R"
        if core and core.getKey and getKeyName then
            local kk = core:getKey("Mordhau_Kick")
            if kk and kk > 0 then kickKeyName = getKeyName(kk) or "F" end
            local gk = core:getKey("Mordhau_AltGrip")
            if gk and gk > 0 then gripKeyName = getKeyName(gk) or "R" end
        end
        local hints = {
            tr("UI_Mordhau_Hint_Parry", "[RMB]  Parry / Feint"),
            tr("UI_Mordhau_Hint_Strike", "[LMB]  360 Strike / Morph"),
            tr("UI_Mordhau_Hint_Thrust", "[Wheel Up]  Thrust / Morph"),
            string.format("[%s]  %s", kickKeyName, tr("UI_Mordhau_Hint_KickAction", "Kick")),
            string.format("[%s]  %s", gripKeyName, tr("UI_Mordhau_Hint_AltGripAction", "Alt-Grip (Half-Sword / Spike)")),
        }
        for i, line in ipairs(hints) do
            local ly = hy + (i - 1) * lineH
            self:drawText(line, hx + 1, ly + 1, 0.0, 0.0, 0.0, 0.75, UIFont.Small)
            self:drawText(line, hx, ly, 0.90, 0.88, 0.82, 0.82, UIFont.Small)
        end
    end
end

local function ensureMordhauHUD()
    if MordhauHUD.instance then
        MordhauHUD.instance:removeFromUIManager()
        MordhauHUD.instance = nil
    end
    local hud = MordhauHUD:new()
    hud:initialise()
    hud:addToUIManager()
    if hud.javaObject then
        hud.javaObject:setConsumeMouseEvents(false)
        hud.javaObject:setWidth(0)
        hud.javaObject:setHeight(0)
    end
    MordhauHUD.instance = hud
end

Events.OnCreatePlayer.Add(function(playerIndex, player)
    if playerIndex == 0 then
        ensureMordhauHUD()
    end
end)

Events.OnGameStart.Add(function()
    ensureMordhauHUD()
end)
