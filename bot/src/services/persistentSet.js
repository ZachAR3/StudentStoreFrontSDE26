const fs = require('fs')

/**
 * A Set saved to a JSON file, for small bot state that must survive restarts
 * (consent declines, recently processed message IDs). Writes are atomic
 * (temp file + rename). With maxSize, the oldest entries are dropped first.
 */
class PersistentSet {
    constructor(filePath, { maxSize = Infinity, saveDelayMs = 0 } = {}) {
        this.filePath = filePath
        this.maxSize = maxSize
        this.saveDelayMs = saveDelayMs
        this.items = new Set()
        this.saveTimer = null
        try {
            const saved = JSON.parse(fs.readFileSync(filePath, 'utf8'))
            if (Array.isArray(saved)) saved.forEach(item => this.items.add(item))
        } catch (error) {
            if (error.code !== 'ENOENT') console.warn(`Could not restore ${filePath}:`, error.message)
        }
    }

    has(item) {
        return this.items.has(item)
    }

    add(item) {
        if (item === undefined || item === null || item === '') return
        this.items.delete(item)
        this.items.add(item)
        while (this.items.size > this.maxSize) {
            this.items.delete(this.items.values().next().value)
        }
        this.scheduleSave()
    }

    delete(item) {
        const removed = this.items.delete(item)
        if (removed) this.scheduleSave()
        return removed
    }

    scheduleSave() {
        if (this.saveDelayMs <= 0) return this.save()
        if (this.saveTimer) return
        this.saveTimer = setTimeout(() => {
            this.saveTimer = null
            this.save()
        }, this.saveDelayMs)
        this.saveTimer.unref?.()
    }

    save() {
        const tmpPath = `${this.filePath}.tmp`
        try {
            fs.writeFileSync(tmpPath, JSON.stringify([...this.items]))
            fs.renameSync(tmpPath, this.filePath)
        } catch (error) {
            console.error(`Could not save ${this.filePath}:`, error.message)
        }
    }
}

module.exports = PersistentSet
