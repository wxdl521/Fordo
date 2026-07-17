#!/usr/bin/env node
/**
 * Fails CI/local builds when any raw (uncompressed) JS chunk under dist/
 * exceeds the hard budget. Prevents accidental reintroduction of full ECharts
 * or other mega-vendor bundles.
 *
 * Limit: 512000 bytes (500 KiB) per JS asset.
 */
import { readdir, stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const LIMIT = 512000
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const distDir = path.resolve(__dirname, '../dist')

async function collectJsFiles(dir) {
  const entries = await readdir(dir, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      files.push(...(await collectJsFiles(full)))
    } else if (entry.isFile() && entry.name.endsWith('.js')) {
      files.push(full)
    }
  }
  return files
}

async function main() {
  let files
  try {
    files = await collectJsFiles(distDir)
  } catch (err) {
    console.error(`[check-bundle-size] Cannot read dist/: ${err.message}`)
    console.error('Run `npm run build` first.')
    process.exit(1)
  }

  if (!files.length) {
    console.error('[check-bundle-size] No JS assets found under dist/. Run `npm run build` first.')
    process.exit(1)
  }

  const oversized = []
  const rows = []
  for (const file of files) {
    const { size } = await stat(file)
    const rel = path.relative(distDir, file).replace(/\\/g, '/')
    rows.push({ rel, size })
    if (size > LIMIT) oversized.push({ rel, size })
  }

  rows.sort((a, b) => b.size - a.size)
  console.log(`[check-bundle-size] limit=${LIMIT} bytes · scanned ${rows.length} JS assets`)
  for (const r of rows.slice(0, 12)) {
    const flag = r.size > LIMIT ? ' FAIL' : ''
    console.log(`  ${String(r.size).padStart(9)}  ${r.rel}${flag}`)
  }

  if (oversized.length) {
    console.error('\n[check-bundle-size] Oversized chunks:')
    for (const o of oversized) {
      console.error(`  ${o.rel}: ${o.size} bytes (limit ${LIMIT})`)
    }
    process.exit(1)
  }

  console.log('[check-bundle-size] OK — all JS chunks within budget.')
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
