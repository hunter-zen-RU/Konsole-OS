# Konsole OS
<img width="1774" height="887" alt="Konsole OS Neon Terminal Banner" src="https://github.com/user-attachments/assets/284fc074-4beb-4515-9fbc-1ed860b3198e" />

[![Build status](https://github.com/termux/termux-app/workflows/Build/badge.svg)](https://github.com/termux/termux-app/actions)
[![Testing status](https://github.com/termux/termux-app/workflows/Unit%20tests/badge.svg)](https://github.com/termux/termux-app/actions)
[![Join the chat at https://gitter.im/termux/termux](https://badges.gitter.im/termux/termux.svg)](https://gitter.im/termux/termux)
[![Join the Termux discord server](https://img.shields.io/discord/641256914684084234.svg?label=&logo=discord&logoColor=ffffff&color=5865F2)](https://discord.gg/HXpF69X)
[![Termux library releases at Jitpack](https://jitpack.io/v/termux/termux-app.svg)](https://jitpack.io/#termux/termux-app)


*KONSOLE OS* is an Android terminal application , a android launcher and Linux environment.

Note that this repository is for the app itself (the user interface and the terminal emulation). For the packages installable inside the app, see 
- [termux/termux-packages](https://github.com/termux/termux-packages).
- [Konsole-packages](https://github.com/hunter-zen-RU/Konsole-packages)
Quick how-to about Termux/konsole package management is available at [Package Management](https://github.com/termux/termux-packages/wiki/Package-Management). It also has info on how to fix **`repository is under maintenance or down`** errors when running `apt` or `pkg` commands.

**We are looking for Konsole Android application maintainers.**

***

**NOTICE: Konsole OS may be unstable on Android 12+.** Android OS will kill any (phantom) processes greater than 32 (limit is for all apps combined) and also kill any processes using excessive CPU. You may get `[Process completed (signal 9) - press Enter]` message in the terminal without actually exiting the shell process yourself. Check the related issue [#2366](https://github.com/termux/termux-app/issues/2366), [issue tracker](https://issuetracker.google.com/u/1/issues/205156966), [phantom cached and empty processes docs](https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md) and [this TLDR comment](https://github.com/termux/termux-app/issues/2366#issuecomment-1237468220) on how to disable trimming of phantom and excessive cpu usage processes. A proper docs page will be added later. An option to disable the killing should be available in Android 12L or 13, so upgrade at your own risk if you are on Android 11, specially if you are not rooted.

***
## Contents
- comming soon
