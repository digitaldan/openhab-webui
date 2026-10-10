import { nextTick, ref, watch } from 'vue'
import { f7 } from 'framework7-vue'
import { useStatesStore } from '@/js/stores/useStatesStore'
import { BridgeError, hasSaidHello, hostDarkMode, hostInfo, hostTakesOver, onHostCommand, sayHello, send } from './bridge'
import { UNLOCK_ID } from './menu'
import { hostNavbarVersion, navbarStateFor, runNavbarAction, runNavbarBack } from './navbar'
import { captureNavState, isModalRoute, restoreProps, takeSeededHistory, type RouterLike } from './routes'
import type { MenuState } from './protocol'

interface F7Page {
  el?: Element
  route?: { url?: string }
}

export interface HostAppOptions {
  version: string
  /** The menu to send as menu.state. Null until it is ready. */
  menu: () => MenuState | null
  /** Opens a popup, popover or sheet, as `kind:target`. */
  openModal: (command: string) => void
  /** Starts sign-in. Returns false when already signed in. */
  unlock: () => boolean
}

/**
 * Talks to the iOS or Android app that Main UI runs in. Sends the current page, top bar, menu and
 * connection state, and handles the app's commands. Call from App's created(), and call start()
 * once Framework7 is ready.
 */
export function useHostApp(options: HostAppOptions) {
  const hostNavbar = hostTakesOver('navbar')
  const hostMenu = hostTakesOver('menu')

  const navbarHeight = ref(hostInfo()?.layout?.navbarHeight)
  const scrolledAway = ref(false)
  const collapsed = ref(false)
  const popups = ref(0)
  const searching = ref(false)

  // Cast to the part of the router the route helpers use.
  const mainRouter = () => f7.views.main?.router as unknown as RouterLike | undefined

  let page: Element | null = null
  let modalOpen = false
  let restore = takeSeededHistory()
  const sent = { nav: '', navbar: '', menu: '' }

  // Set on <html>, because f7-app ignores class and style bindings.
  document.documentElement.classList.toggle('host-navbar', hostNavbar)
  document.documentElement.classList.toggle('host-menu', hostMenu)
  watch(
    navbarHeight,
    (height) => {
      if (hostNavbar && height) document.documentElement.style.setProperty('--f7-navbar-height', height + 'px')
    },
    { immediate: true }
  )

  function sendMenu() {
    const menu = options.menu()
    if (!hostMenu || !hasSaidHello() || !menu) return
    const json = JSON.stringify(menu)
    if (json === sent.menu) return
    sent.menu = json
    send('menu.state', menu)
  }

  function sendNavbar() {
    if (!hostNavbar || !hasSaidHello()) return
    // Hide the app's bar while a popup or an expanded searchbar shows its own bar.
    const hidden = scrolledAway.value || popups.value > 0 || searching.value
    const state = navbarStateFor(page, { hidden, collapsed: collapsed.value })
    const json = JSON.stringify(state)
    if (json === sent.navbar) return
    sent.navbar = json
    send('navbar.state', state)
  }

  function sendNav() {
    if (!hasSaidHello() || restore) return
    const state = captureNavState(mainRouter()!, modalOpen)
    if (!state) return
    const json = JSON.stringify(state)
    if (json === sent.nav) return
    sent.nav = json
    send('nav.changed', state)
  }

  // Called for every page shown. The first one sends the hello and finishes the page restore.
  function pageShown(shown: F7Page) {
    if (!shown.el || shown.el.closest('.popup')) return
    page = shown.el
    modalOpen = false
    scrolledAway.value = false
    collapsed.value = false
    // Restore props only if the first page is the last saved page. A redirect can land elsewhere.
    if (restore) {
      const pages = restore
      restore = null
      const router = mainRouter()
      if (router && shown.route?.url === pages[pages.length - 1]) restoreProps(router, pages, hostInfo()?.initialProps)
    }
    sayHello(options.version)
    sendNav()
    sendNavbar()
    sendMenu()
  }

  watch(options.menu, sendMenu)
  watch(
    () => useStatesStore().sseConnected,
    (connected) => send('connection.state', { sseConnected: !!connected }),
    { immediate: true }
  )
  watch([scrolledAway, collapsed, popups, searching], sendNavbar)
  watch(hostNavbarVersion, () => {
    void nextTick(sendNavbar)
  })

  function openModals() {
    return Array.from(document.querySelectorAll<HTMLElement>('.popup.modal-in, .popover.modal-in, .sheet-modal.modal-in'))
  }

  function closeModal(el: HTMLElement) {
    if (el.classList.contains('popup')) f7.popup.close(el)
    else if (el.classList.contains('popover')) f7.popover.close(el)
    else f7.sheet.close(el)
  }

  function registerCommands() {
    const router = () => f7.views.main.router
    onHostCommand('nav.navigate', ({ path }: { path?: string }) => {
      if (!path) throw new BridgeError('not_found')
      router().navigate(path)
    })
    // Close an open popup, popover or sheet before going back.
    onHostCommand('nav.back', () => {
      const open = openModals().pop()
      if (open) closeModal(open)
      else if (!runNavbarBack(page)) router().back()
    })
    onHostCommand('nav.openModal', ({ kind, target }: { kind?: string; target?: string }) => {
      if (!['popup', 'popover', 'sheet'].includes(kind ?? '') || !/^(page:|widget:|oh-)/.test(target ?? ''))
        throw new BridgeError('not_found')
      options.openModal(kind + ':' + target)
    })
    onHostCommand('nav.closeModals', () => openModals().forEach(closeModal))
    onHostCommand('nav.getState', () => captureNavState(mainRouter()!, modalOpen))
    onHostCommand('navbar.activate', ({ id }: { id: string }) => {
      if (!runNavbarAction(page, id)) throw new BridgeError('not_found')
    })
    onHostCommand('menu.activate', ({ id }: { id: string }) => {
      if (id !== UNLOCK_ID || !options.unlock()) throw new BridgeError('not_found')
    })
    onHostCommand('layout.changed', ({ navbarHeight: height }: { navbarHeight?: number }) => {
      if (height && height > 0) navbarHeight.value = height
    })
    onHostCommand('settings.changed', ({ darkMode }: { darkMode?: string }) => {
      if (darkMode === 'light' || darkMode === 'dark') hostDarkMode.value = darkMode
    })
    onHostCommand('ui.reload', () => {
      setTimeout(() => window.location.reload(), 0)
    })
  }

  /** Adds the Framework7 listeners and command handlers. Call once Framework7 is ready. */
  function start() {
    f7.on('routeChange', (route) => {
      modalOpen = !!route && isModalRoute(route)
      void nextTick(() => {
        sendNav()
        sendNavbar()
      })
    })
    f7.on('pageAfterIn', pageShown)
    f7.on('routeUrlUpdate', () => {
      void nextTick(sendNav)
    })
    if (hostNavbar) {
      f7.on('navbarHide', () => (scrolledAway.value = true))
      f7.on('navbarShow', () => (scrolledAway.value = false))
      f7.on('navbarCollapse', () => (collapsed.value = true))
      f7.on('navbarExpand', () => (collapsed.value = false))
      const countPopups = () => (popups.value = document.querySelectorAll('.popup.modal-in').length)
      f7.on('popupOpen', countPopups)
      f7.on('popupClose', countPopups)
      f7.on('popupClosed', countPopups)
      f7.on('searchbarEnable', (searchbar) => (searching.value = !!searchbar?.expandable))
      f7.on('searchbarDisable', () => (searching.value = false))
    }
    registerCommands()
  }

  return { hostNavbar, hostMenu, start }
}
