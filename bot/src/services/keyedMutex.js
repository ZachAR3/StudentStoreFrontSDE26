/**
 * Runs async work one at a time per key (BOT-09). A double "yes" or a timer
 * firing while a reply is handled would otherwise submit the same draft twice
 * in parallel.
 */
class KeyedMutex {
    constructor() {
        this.tails = new Map()
    }

    run(key, task) {
        const previous = this.tails.get(key) || Promise.resolve()
        const result = previous.catch(() => {}).then(task)
        const tail = result.catch(() => {})
        this.tails.set(key, tail)
        tail.then(() => {
            if (this.tails.get(key) === tail) this.tails.delete(key)
        })
        return result
    }
}

module.exports = KeyedMutex
