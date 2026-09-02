export interface NavItem {
  label: string
  routeName: string
  icon: string
  adminOnly?: boolean
}

export const mainNavItems: NavItem[] = [
  {
    label: 'Live-Anwesenheit',
    routeName: 'live-anwesenheit',
    icon: 'pi-circle-fill'
  },
  { label: 'Klassen', routeName: 'klassen', icon: 'pi-sitemap' },
  { label: 'Schüler', routeName: 'schueler', icon: 'pi-users' },
  {
    label: 'Stunden- und Blockplan',
    routeName: 'planung',
    icon: 'pi-calendar'
  },
  { label: 'Auswertungen', routeName: 'auswertungen', icon: 'pi-chart-bar' }
]

export const administrationNavItems: NavItem[] = [
  { label: 'Räume', routeName: 'raeume', icon: 'pi-building', adminOnly: true },
  {
    label: 'Terminals',
    routeName: 'terminals',
    icon: 'pi-desktop',
    adminOnly: true
  },
  {
    label: 'Personal',
    routeName: 'personal',
    icon: 'pi-id-card',
    adminOnly: true
  }
]
