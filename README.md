# Feeding Carrot Compat

[中文](#中文) · [English](#english)

## 中文

让玩家之间的喂食采用长按操作，并正确计入「生活调味料：胡萝卜版」的食物进度。

### 功能

- 长按右键完成喂食，持续按住可继续喂下一份；取消操作不会消耗食物。
- 由被喂玩家获得食物记录与相应奖励。
- 玩家可在许可面板中选择谁能给自己喂食，默认允许所有人。
- 不被允许的玩家刚开始喂食就会收到提示。
- 沿用原喂食模组的饱食度与食物黑名单配置。

### 使用

适用于 Minecraft **1.20.1 / Forge**，服务端和客户端均需安装。需要 **Let Me Feed You! 1.0.0** 和 **Spice of Life: Carrot Edition 1.15.1**。

主手拿食物，对准玩家长按右键。许可面板的快捷键默认未绑定，可在游戏按键设置中配置。

仓库另含独立的 [Enigmatic 启动修复](src/enigmaticfix/README.md)。

## English

Add hold-to-feed interactions between players and count received food toward Spice of Life: Carrot Edition progression.

### Features

- Hold right-click to feed, and keep holding to feed another portion. Cancelling does not consume food.
- Credit the recipient with food discoveries and progression rewards.
- Let each player choose who may feed them; everyone is allowed by default.
- Notify disallowed players as soon as they try to feed someone.
- Respect the original feeding mod’s hunger and food-blacklist settings.

### Usage

For Minecraft **1.20.1 / Forge**. Install on both the server and clients, alongside **Let Me Feed You! 1.0.0** and **Spice of Life: Carrot Edition 1.15.1**.

Hold food in your main hand, aim at a player, and hold right-click. Set the permissions-panel keybind in Controls; it is unbound by default.

The repository also contains a separate [Enigmatic startup fix](src/enigmaticfix/README.md).

---

原作者 / Original author: [zcpu · PixelMC](https://github.com/zcpu954861).

[AGPL-3.0-only](LICENSE) · [Minecraft linking permission](LICENSE-EXCEPTION.md).
保留版权与署名；分发修改版或使用修改版提供联网服务时，按许可证提供对应源码。
Retain copyright and attribution; provide corresponding source when distributing modifications or offering modified software over a network, as required by the license.
