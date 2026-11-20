const {execFile} = require('node:child_process');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {randomUUID} = require('node:crypto');

const directory = path.join(process.env.LOCALAPPDATA || os.homedir(), 'NeuralLink');
const vaultFile = path.join(directory, 'providers.dpapi');
const powershell = path.join(process.env.SystemRoot || 'C:/Windows', 'System32',
    'WindowsPowerShell', 'v1.0', 'powershell.exe');

function transform(data, operation) {
    if (process.platform !== 'win32') {
        return Promise.reject(new Error('The preview credential vault requires Windows.'));
    }
    const script = [
        "$ErrorActionPreference = 'Stop'",
        'Add-Type -AssemblyName System.Security',
        '$bytes = [Convert]::FromBase64String([Console]::In.ReadToEnd())',
        '$scope = [Security.Cryptography.DataProtectionScope]::CurrentUser',
        '$result = [Security.Cryptography.ProtectedData]::' + operation + '($bytes, $null, $scope)',
        '[Console]::Write([Convert]::ToBase64String($result))'
    ].join('\n');

    return new Promise((resolve, reject) => {
        const child = execFile(powershell, ['-NoProfile', '-NonInteractive', '-Command', script],
            {windowsHide: true, timeout: 20_000, maxBuffer: 32_000_000},
            (error, stdout) => {
                if (error) reject(new Error('Windows could not access the credential vault.'));
                else resolve(Buffer.from(stdout.trim(), 'base64'));
            });
        child.stdin.on('error', () => {});
        child.stdin.end(data.toString('base64'));
    });
}

async function load() {
    let encrypted;
    try {
        encrypted = await fs.readFile(vaultFile);
    } catch (error) {
        if (error.code === 'ENOENT') return [];
        throw error;
    }
    const records = JSON.parse((await transform(encrypted, 'Unprotect')).toString('utf8'));
    if (!Array.isArray(records) || records.some(record => !record?.id
            || typeof record.key !== 'string' || !Array.isArray(record.models))) {
        throw new Error('The credential vault has an invalid format.');
    }
    return records;
}

async function save(records) {
    const encrypted = await transform(Buffer.from(JSON.stringify(records), 'utf8'), 'Protect');
    await fs.mkdir(directory, {recursive: true});
    const temporaryFile = vaultFile + '.' + randomUUID() + '.tmp';
    try {
        await fs.writeFile(temporaryFile, encrypted, {flag: 'wx'});
        await fs.rename(temporaryFile, vaultFile);
    } finally {
        await fs.rm(temporaryFile, {force: true});
    }
}

module.exports = {load, save};
