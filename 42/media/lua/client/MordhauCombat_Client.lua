-- MordhauCombat_Client.lua
-- MordhauCombat v0.2 Client Integration:
-- 1. Registers rebindable keys directly in Project Zomboid B42's native "Options -> Keybinds"
--    ([MordhauCombat] section, saved in keysB42.ini) with zero conflicts with Viewpoint Advanced Movement (Q/E/Space/C/Z).
-- 2. Implements Smart F Kick: pressing F performs a 3D Kick in combat when no door/window/loot is targeted
--    right under the crosshair, and opens doors/windows/loot normally otherwise!
-- 3. Registers Mordhau tuning options (Mouse Sensitivity, Wall Hit-Stop, HUD Hints, Axis Invert) in "Options -> Mods".

require "MordhauCombat_HUD"

MordhauClient = MordhauClient or {}
MordhauClient.options = nil

local HEADER = "[MordhauCombat]"

local BINDS = {
    { name = "Mordhau_Kick",        key = Keyboard.KEY_F },
    { name = "Mordhau_AltGrip",     key = Keyboard.KEY_R },
    { name = "Mordhau_Feint",       key = Keyboard.KEY_G },
    { name = "Mordhau_Thrust",      key = Keyboard.KEY_T },
    { name = "Mordhau_ToggleHUD",   key = Keyboard.KEY_H },
    { name = "Mordhau_Parry",       key = 0 },
    { name = "Mordhau_Overhead",    key = 0 },
    { name = "Mordhau_Underhand",   key = 0 },
    { name = "Mordhau_StrikeLeft",  key = 0 },
    { name = "Mordhau_StrikeRight", key = 0 },
}

local function tr(key, fallback)
    if getText then
        local res = getText(key)
        if res and res ~= key then
            return res
        end
    end
    return fallback or key
end

-- =========================================================================
-- 1. Native B42 "Options -> Keybinds" Registration (keysB42.ini)
-- =========================================================================

local function addNativeKeyBindings()
    if type(keyBinding) ~= "table" then return false end
    for _, v in ipairs(keyBinding) do
        if v.value == HEADER then return true end
    end
    local at = #keyBinding + 1
    for i, v in ipairs(keyBinding) do
        if v.value == "[Debug]" then
            at = i
            break
        end
    end
    table.insert(keyBinding, at, { value = HEADER })
    for i, b in ipairs(BINDS) do
        table.insert(keyBinding, at + i, { value = b.name, key = b.key })
    end
    return true
end

local function keyEntry(name)
    if not (MainOptions and MainOptions.keys) then return nil end
    for _, v in ipairs(MainOptions.keys) do
        if v.value == name then return v end
    end
    return nil
end

local function wrapMainOptions()
    if not MainOptions then return end
    if MainOptions.loadKeys and not MainOptions.mordhauWrapped then
        local original = MainOptions.loadKeys
        MainOptions.loadKeys = function(...)
            addNativeKeyBindings()
            return original(...)
        end
        MainOptions.mordhauWrapped = true
    end
    if MainOptions.addModOptionsPanel and not MainOptions.mordhauModOptsWrapped then
        local origAddModOpts = MainOptions.addModOptionsPanel
        MainOptions.addModOptionsPanel = function(self, ...)
            if MordhauClient.descEntry and getText then
                MordhauClient.descEntry.text = getText("UI_Mordhau_Desc")
            end
            return origAddModOpts(self, ...)
        end
        MainOptions.mordhauModOptsWrapped = true
    end
end

local function ensureNativeKeysLoaded()
    addNativeKeyBindings()
    wrapMainOptions()
    if MainOptions and MainOptions.loadKeys and not keyEntry(BINDS[1].name) then
        MainOptions.loadKeys()
    end
end

addNativeKeyBindings()
wrapMainOptions()
Events.OnGameBoot.Add(function()
    addNativeKeyBindings()
    wrapMainOptions()
end)

local function isBindPressed(bindName, defaultKey, pressedKey)
    if not pressedKey or pressedKey <= 0 then return false end
    local core = getCore and getCore() or nil
    if core and core.getKey then
        local k1 = core:getKey(bindName)
        local k2 = core.getAltKey and core:getAltKey(bindName) or 0
        if (k1 and k1 > 0) or (k2 and k2 > 0) then
            return pressedKey == k1 or pressedKey == k2
        end
        if keyEntry(bindName) then
            -- Explicitly unbound (0) by user in Options -> Keybinds
            return false
        end
    end
    return defaultKey and defaultKey > 0 and pressedKey == defaultKey
end

-- =========================================================================
-- 2. B42 "Options -> Mods" Tuning Settings (PZAPI.ModOptions)
-- =========================================================================

local function setupModOptions()
    if PZAPI and PZAPI.ModOptions then
        local opts = PZAPI.ModOptions:create("MordhauCombat", "UI_Mordhau_ModName")
        MordhauClient.options = opts

        opts:addTitle("UI_Mordhau_Title")
        opts:addDescription("UI_Mordhau_Desc")
        MordhauClient.descEntry = opts.data[#opts.data]

        MordhauClient.sensSlider = opts:addSlider(
            "Mordhau_Sensitivity",
            "UI_Mordhau_Opt_Sensitivity",
            0.2,
            3.0,
            0.1,
            1.0,
            "UI_Mordhau_Opt_Sensitivity_Tooltip"
        )

        MordhauClient.wallHitStopBox = opts:addTickBox(
            "Mordhau_WallHitStop",
            "UI_Mordhau_Opt_WallHitStop",
            true,
            "UI_Mordhau_Opt_WallHitStop_Tooltip"
        )

        MordhauClient.showHintsBox = opts:addTickBox(
            "Mordhau_ShowHints",
            "UI_Mordhau_Opt_ShowHints",
            true,
            "UI_Mordhau_Opt_ShowHints_Tooltip"
        )

        MordhauClient.invertXBox = opts:addTickBox(
            "Mordhau_InvertX",
            "UI_Mordhau_Opt_InvertX",
            false,
            "UI_Mordhau_Opt_InvertX_Tooltip"
        )

        MordhauClient.invertYBox = opts:addTickBox(
            "Mordhau_InvertY",
            "UI_Mordhau_Opt_InvertY",
            false,
            "UI_Mordhau_Opt_InvertY_Tooltip"
        )

        opts.apply = function(self)
            MordhauClient.applySettings()
        end

        if MainOptions and MainOptions.addModOptionsPanel and not MainOptions.mordhauModOptsWrapped then
            local origAddModOpts = MainOptions.addModOptionsPanel
            MainOptions.addModOptionsPanel = function(self, ...)
                if MordhauClient.descEntry and getText then
                    MordhauClient.descEntry.text = getText("UI_Mordhau_Desc")
                end
                return origAddModOpts(self, ...)
            end
            MainOptions.mordhauModOptsWrapped = true
        end
    end
end

function MordhauClient.applySettings()
    if not MordhauClient.options then
        return
    end
    if MordhauClient.sensSlider and _G.Mordhau_setMouseSensitivity then
        local val = MordhauClient.sensSlider:getValue()
        if val then
            _G.Mordhau_setMouseSensitivity(val)
        end
    end
    if MordhauClient.wallHitStopBox and _G.Mordhau_setWallHitStop then
        _G.Mordhau_setWallHitStop(MordhauClient.wallHitStopBox:getValue() == true)
    end
    if MordhauClient.showHintsBox then
        MordhauHUD.showKeyHints = (MordhauClient.showHintsBox:getValue() == true)
    end
    if MordhauClient.invertXBox and _G.Mordhau_setInvertMouseX then
        _G.Mordhau_setInvertMouseX(MordhauClient.invertXBox:getValue() == true)
    end
    if MordhauClient.invertYBox and _G.Mordhau_setInvertMouseY then
        _G.Mordhau_setInvertMouseY(MordhauClient.invertYBox:getValue() == true)
    end
end

setupModOptions()

-- =========================================================================
-- 3. In-Game Key Dispatch (Smart F Kick, Alt-Grip, Feint, HUD Toggle)
-- =========================================================================

local function isTypingOrMenuOpen()
    if isGamePaused and isGamePaused() then return true end
    if MainScreen and MainScreen.instance and MainScreen.instance:isVisible() then return true end
    if ISChat and ISChat.instance and ISChat.instance.chatText and ISChat.instance.chatText.focus then return true end
    local focus = UIManager and UIManager.getFocus and UIManager.getFocus() or nil
    if focus and instanceof(focus, "ISTextEntryBox") then return true end
    return false
end

local function onKeyPressed(key)
    if isTypingOrMenuOpen() then
        return
    end

    local player = getSpecificPlayer(0)
    if not player or player:isDead() or player:getVehicle() then
        return
    end

    if isBindPressed("Mordhau_ToggleHUD", Keyboard.KEY_H, key) then
        MordhauHUD.enabled = not MordhauHUD.enabled
        return
    end

    -- Smart Kick (Default: F): works BOTH armed AND unarmed!
    -- If Kick shares the same key as "Interact" (e.g. F with Viewpoint Advanced Movement),
    -- automatically yields to doors/windows/loot when aiming right at an interactable object!
    if isBindPressed("Mordhau_Kick", Keyboard.KEY_F, key) then
        local core = getCore and getCore() or nil
        local interactKey = (core and core.getKey and core:getKey("Interact")) or Keyboard.KEY_E
        local interactAlt = (core and core.getAltKey and core:getAltKey("Interact")) or 0
        local sharedWithInteract = (key == interactKey) or (interactAlt > 0 and key == interactAlt)
        if _G.Mordhau_trySmartKick then
            _G.Mordhau_trySmartKick(sharedWithInteract)
        elseif _G.Mordhau_triggerKick then
            _G.Mordhau_triggerKick()
        end
        return
    end

    local item = player:getPrimaryHandItem()
    if not item or not instanceof(item, "HandWeapon") or item:isRanged() or item:isBareHands() then
        return
    end

    if isBindPressed("Mordhau_AltGrip", Keyboard.KEY_R, key) then
        if _G.Mordhau_toggleAltGrip then
            _G.Mordhau_toggleAltGrip()
        end
    elseif isBindPressed("Mordhau_Feint", Keyboard.KEY_G, key) then
        if _G.Mordhau_triggerFeint then
            _G.Mordhau_triggerFeint()
        end
    elseif isBindPressed("Mordhau_Thrust", Keyboard.KEY_T, key) then
        if _G.Mordhau_triggerThrust then
            _G.Mordhau_triggerThrust()
        end
    elseif isBindPressed("Mordhau_Parry", 0, key) then
        if _G.Mordhau_triggerParryOrFeint then
            _G.Mordhau_triggerParryOrFeint()
        end
    elseif isBindPressed("Mordhau_Overhead", 0, key) then
        if _G.Mordhau_setDirection then _G.Mordhau_setDirection("overhead") end
        if _G.Mordhau_triggerStrike then _G.Mordhau_triggerStrike("overhead") end
    elseif isBindPressed("Mordhau_Underhand", 0, key) then
        if _G.Mordhau_setDirection then _G.Mordhau_setDirection("underhand") end
        if _G.Mordhau_triggerStrike then _G.Mordhau_triggerStrike("underhand") end
    elseif isBindPressed("Mordhau_StrikeLeft", 0, key) then
        if _G.Mordhau_setDirection then _G.Mordhau_setDirection("left") end
    elseif isBindPressed("Mordhau_StrikeRight", 0, key) then
        if _G.Mordhau_setDirection then _G.Mordhau_setDirection("right") end
    end
end

Events.OnKeyPressed.Add(onKeyPressed)
Events.OnGameStart.Add(function()
    ensureNativeKeysLoaded()
    MordhauClient.applySettings()
end)

function MordhauClient.notifyNpcAttack(npcIsoGameCharacter, directionString, windupSeconds)
    if _G.Mordhau_registerNpcAttack and npcIsoGameCharacter then
        _G.Mordhau_registerNpcAttack(npcIsoGameCharacter, directionString or "right", windupSeconds or 0.45)
    end
end
