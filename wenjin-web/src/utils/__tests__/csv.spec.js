import { describe, it, expect } from 'vitest'
import { escapeCsvCell, toCsv } from '../csv.js'

describe('escapeCsvCell', () => {
  it('neutralises formula-leading cells with a single quote', () => {
    expect(escapeCsvCell('=1+1')).toBe("'=1+1")
    expect(escapeCsvCell('@SUM(A1:A2)')).toBe("'@SUM(A1:A2)")
    expect(escapeCsvCell('+cmd')).toBe("'+cmd")
    expect(escapeCsvCell('-cmd')).toBe("'-cmd")
    expect(escapeCsvCell('\tfoo')).toBe("'\tfoo")
    expect(escapeCsvCell('\rfoo')).toBe("\"'\rfoo\"")
  })

  it('neutralises HYPERLINK and DDE payloads then still quotes', () => {
    expect(escapeCsvCell('=HYPERLINK("http://x","y")')).toBe('"\'=HYPERLINK(""http://x"",""y"")"')
    expect(escapeCsvCell("=cmd|' /C calc'!A0")).toBe("'=cmd|' /C calc'!A0")
  })

  it('leaves strictly numeric cells untouched', () => {
    expect(escapeCsvCell('-12.3')).toBe('-12.3')
    expect(escapeCsvCell('+5')).toBe('+5')
    expect(escapeCsvCell(-3)).toBe('-3')
    expect(escapeCsvCell('-')).toBe("'-")
  })

  it('still applies RFC4180 quoting', () => {
    expect(escapeCsvCell('a,b')).toBe('"a,b"')
    expect(escapeCsvCell('say "hi"')).toBe('"say ""hi"""')
    expect(escapeCsvCell('l1\nl2')).toBe('"l1\nl2"')
    expect(escapeCsvCell(null)).toBe('')
  })

  it('toCsv applies escaping to every row', () => {
    expect(toCsv(['n'], [['=x'], ['ok']])).toBe("n\r\n'=x\r\nok")
  })
})
