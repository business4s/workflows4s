const fs = require('fs');
const path = require('path');

const GITHUB_BASE = 'https://github.com/business4s/workflows4s/blob/main/workflows4s-example/src';

const fileCache = new Map();

function readFileLines(absPath) {
  if (fileCache.has(absPath)) return fileCache.get(absPath);
  const lines = fs.readFileSync(absPath, 'utf8').split('\n');
  fileCache.set(absPath, lines);
  return lines;
}

function parseMeta(meta) {
  if (!meta) return null;
  const fileMatch = meta.match(/file=(\S+)/);
  if (!fileMatch) return null;
  const startMatch = meta.match(/start=(\S+)/);
  const endMatch = meta.match(/end=(\S+)/);
  return {
    file: fileMatch[1],
    start: startMatch ? startMatch[1] : null,
    end: endMatch ? endMatch[1] : null,
  };
}

function findLineNumber(lines, marker) {
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].includes(marker)) return i + 1;
  }
  return null;
}

function buildGithubUrl(baseDir, filePath, start, end) {
  const base = `${GITHUB_BASE}/${filePath.replace(/^\.\//, '')}`;
  if (!start && !end) return base;

  let lines;
  try {
    lines = readFileLines(path.resolve(baseDir, filePath));
  } catch {
    return base;
  }

  const startLine = start ? findLineNumber(lines, start) : null;
  const endLine = end ? findLineNumber(lines, end) : null;

  if (start && !startLine) {
    console.warn(`[source-link] start marker "${start}" not found in ${filePath}`);
  }
  if (end && !endLine) {
    console.warn(`[source-link] end marker "${end}" not found in ${filePath}`);
  }

  // Snippet content is between the markers (exclusive), so +1 on start, -1 on end
  const from = startLine ? startLine + 1 : 1;
  const to = endLine ? endLine - 1 : lines.length;

  return `${base}#L${from}-L${to}`;
}

function remarkSourceLink(options = {}) {
  const baseDir = path.resolve(options.baseDir || '.');

  return (tree) => {
    const replacements = [];

    function walk(node, index, parent) {
      if (node.type === 'code') {
        const meta = parseMeta(node.meta);
        if (meta && parent) {
          const githubUrl = buildGithubUrl(baseDir, meta.file, meta.start, meta.end);
          replacements.push({ parent, index, codeNode: node, githubUrl });
        }
      }
      if (node.children) {
        node.children.forEach((child, i) => walk(child, i, node));
      }
    }

    walk(tree, 0, null);

    for (const { parent, index, codeNode, githubUrl } of replacements.reverse()) {
      const wrapper = {
        type: 'mdxJsxFlowElement',
        name: 'div',
        attributes: [
          { type: 'mdxJsxAttribute', name: 'data-source-url', value: githubUrl },
        ],
        children: [codeNode],
      };
      parent.children.splice(index, 1, wrapper);
    }
  };
}

module.exports = remarkSourceLink;
