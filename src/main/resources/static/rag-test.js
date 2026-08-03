class RagTestApp {
    constructor() {
        this.apiBaseUrl = 'http://localhost:9900/api/rag-test';
        this.testCases = [];
        this.testResults = [];
        this.summary = null;
        this.bindElements();
        this.bindEvents();
        this.renderTestCases();
        this.renderResults();
    }

    bindElements() {
        this.topicInput = document.getElementById('topicInput');
        this.countInput = document.getElementById('countInput');
        this.difficultySelect = document.getElementById('difficultySelect');
        this.referenceInput = document.getElementById('referenceInput');
        this.generateBtn = document.getElementById('generateBtn');
        this.addCaseBtn = document.getElementById('addCaseBtn');
        this.runBtn = document.getElementById('runBtn');
        this.testCasesContainer = document.getElementById('testCasesContainer');
        this.resultsContainer = document.getElementById('resultsContainer');
        this.summaryContainer = document.getElementById('summaryContainer');
        this.toast = document.getElementById('toast');
        this.importTestSetBtn = document.getElementById('importTestSetBtn');
        this.importTestSetInput = document.getElementById('importTestSetInput');
        this.exportTestSetJsonBtn = document.getElementById('exportTestSetJsonBtn');
        this.exportTestSetCsvBtn = document.getElementById('exportTestSetCsvBtn');
        this.exportResultsJsonBtn = document.getElementById('exportResultsJsonBtn');
        this.exportResultsCsvBtn = document.getElementById('exportResultsCsvBtn');
    }

    bindEvents() {
        this.generateBtn.addEventListener('click', () => this.generateTestSet());
        this.addCaseBtn.addEventListener('click', () => this.addEmptyCase());
        this.runBtn.addEventListener('click', () => this.runTests());
        this.importTestSetBtn.addEventListener('click', () => this.importTestSetInput.click());
        this.importTestSetInput.addEventListener('change', event => this.importTestSet(event));
        this.exportTestSetJsonBtn.addEventListener('click', () => this.exportTestSet('json'));
        this.exportTestSetCsvBtn.addEventListener('click', () => this.exportTestSet('csv'));
        this.exportResultsJsonBtn.addEventListener('click', () => this.exportResults('json'));
        this.exportResultsCsvBtn.addEventListener('click', () => this.exportResults('csv'));
    }

    async generateTestSet() {
        this.setBusy(true, '正在生成...');
        try {
            const response = await this.postJson('/generate', {
                topic: this.topicInput.value.trim(),
                count: Number(this.countInput.value || 5),
                difficulty: this.difficultySelect.value,
                reference: this.referenceInput.value.trim()
            });
            this.testCases = response.data || [];
            this.testResults = [];
            this.summary = null;
            this.renderTestCases();
            this.renderResults();
            this.showToast(`已生成 ${this.testCases.length} 条测试用例`);
        } catch (error) {
            this.showToast(error.message || '生成测试集失败');
        } finally {
            this.setBusy(false);
        }
    }

    addEmptyCase() {
        this.testCases.push({
            id: `case-${this.testCases.length + 1}`,
            question: '',
            expectedAnswer: '',
            expectedKeywords: [],
            expectedDocIds: [],
            source: 'manual',
            metadata: {}
        });
        this.renderTestCases();
    }

    async runTests() {
        this.syncCasesFromTable();
        if (this.testCases.length === 0) {
            this.showToast('请先生成或新增测试用例');
            return;
        }
        if (this.testCases.some(item => !item.question || !item.question.trim())) {
            this.showToast('测试问题不能为空');
            return;
        }

        this.setBusy(true, '测试执行中...');
        try {
            const response = await this.postJson('/run', { testCases: this.testCases });
            this.summary = response.data?.summary || null;
            this.testResults = response.data?.results || [];
            this.renderResults();
            this.showToast('测试执行完成');
        } catch (error) {
            this.showToast(error.message || '执行测试失败');
        } finally {
            this.setBusy(false);
        }
    }

    async importTestSet(event) {
        const file = event.target.files?.[0];
        if (!file) {
            return;
        }

        try {
            const content = await file.text();
            const parsed = JSON.parse(content);
            const importedCases = this.normalizeImportedTestCases(parsed);
            if (importedCases.length === 0) {
                throw new Error('文件中没有有效测试用例');
            }
            this.testCases = importedCases;
            this.testResults = [];
            this.summary = null;
            this.renderTestCases();
            this.renderResults();
            this.showToast(`已导入 ${this.testCases.length} 条测试用例`);
        } catch (error) {
            this.showToast(error.message || '导入测试集失败');
        } finally {
            event.target.value = '';
        }
    }

    async exportTestSet(format) {
        this.syncCasesFromTable();
        if (this.testCases.length === 0) {
            this.showToast('没有可导出的测试集');
            return;
        }
        await this.download('/export/testset', { format, testCases: this.testCases }, `rag-testset.${format}`);
    }

    async exportResults(format) {
        if (this.testResults.length === 0) {
            this.showToast('没有可导出的测试结果');
            return;
        }
        await this.download('/export/results', { format, results: this.testResults }, `rag-test-results.${format}`);
    }

    renderTestCases() {
        if (this.testCases.length === 0) {
            this.testCasesContainer.innerHTML = '<div class="rag-empty">暂无测试集，请先生成或手动新增。</div>';
            return;
        }
        const rows = this.testCases.map((item, index) => `
            <tr data-index="${index}">
                <td><input data-field="id" value="${this.escapeAttr(item.id || '')}" placeholder="case-id"></td>
                <td><textarea data-field="question" placeholder="输入测试问题">${this.escapeHtml(item.question || '')}</textarea></td>
                <td><textarea data-field="expectedAnswer" placeholder="期望答案">${this.escapeHtml(item.expectedAnswer || '')}</textarea></td>
                <td><input data-field="expectedKeywords" value="${this.escapeAttr((item.expectedKeywords || []).join('|'))}" placeholder="关键词用 | 分隔"></td>
                <td><input data-field="expectedDocIds" value="${this.escapeAttr((item.expectedDocIds || []).join('|'))}" placeholder="文档ID用 | 分隔"></td>
                <td><button class="rag-btn danger" data-action="delete" data-index="${index}">删除</button></td>
            </tr>
        `).join('');
        this.testCasesContainer.innerHTML = `
            <table class="rag-table">
                <thead><tr><th>ID</th><th>问题</th><th>期望答案</th><th>关键词</th><th>期望文档</th><th>操作</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
        `;
        this.testCasesContainer.querySelectorAll('[data-action="delete"]').forEach(button => {
            button.addEventListener('click', () => {
                this.testCases.splice(Number(button.dataset.index), 1);
                this.renderTestCases();
            });
        });
    }

    renderResults() {
        this.renderSummary();
        if (this.testResults.length === 0) {
            this.resultsContainer.innerHTML = '<div class="rag-empty">暂无测试结果。</div>';
            return;
        }
        const rows = this.testResults.map(result => `
            <tr>
                <td>${this.escapeHtml(result.caseId || '')}</td>
                <td>${this.escapeHtml(result.question || '')}</td>
                <td>${this.escapeHtml(result.actualAnswer || '')}</td>
                <td>${this.escapeHtml((result.retrievedDocuments || []).map(doc => `${doc.id || '-'}(${Number(doc.score || 0).toFixed(3)})`).join('\n'))}</td>
                <td>${result.hitExpectedDoc ? '是' : '否'}</td>
                <td>${this.percent(result.keywordRecall)}</td>
                <td>${this.percent(result.topKRecall)}</td>
                <td>${this.percent(result.mrr)}</td>
                <td>${this.percent(result.ndcg)}</td>
                <td>${this.percent(result.answerRelevancy)}</td>
                <td>${result.firstTokenMs >= 0 ? result.firstTokenMs + ' ms' : '-'}</td>
                <td>${result.latencyMs || 0} ms</td>
                <td class="${result.passed ? 'status-pass' : 'status-fail'}">${result.passed ? '通过' : '未通过'}</td>
                <td>${this.escapeHtml(result.errorMessage || '')}</td>
            </tr>
        `).join('');
        this.resultsContainer.innerHTML = `
            <table class="rag-table">
                <thead><tr><th>ID</th><th>问题</th><th>实际答案</th><th>检索文档</th><th>命中文档</th><th>关键词覆盖</th><th>TopK 召回</th><th>MRR</th><th>NDCG</th><th>Answer Relevancy</th><th>首字</th><th>耗时</th><th>状态</th><th>错误</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
        `;
    }

    renderSummary() {
        if (!this.summary) {
            this.summaryContainer.innerHTML = '';
            return;
        }
        this.summaryContainer.innerHTML = `
            <div class="rag-summary">
                <div class="summary-item"><span>通过率</span><strong>${this.percent(this.summary.passRate)}</strong></div>
                <div class="summary-item"><span>通过/总数</span><strong>${this.summary.passed}/${this.summary.total}</strong></div>
                <div class="summary-item"><span>文档命中率</span><strong>${this.percent(this.summary.expectedDocHitRate)}</strong></div>
                <div class="summary-item"><span>TopK 召回率</span><strong>${this.percent(this.summary.averageTopKRecall)}</strong></div>
                <div class="summary-item"><span>MRR</span><strong>${this.percent(this.summary.averageMrr)}</strong></div>
                <div class="summary-item"><span>NDCG</span><strong>${this.percent(this.summary.averageNdcg)}</strong></div>
                <div class="summary-item"><span>Answer Relevancy</span><strong>${this.percent(this.summary.averageAnswerRelevancy)}</strong></div>
                <div class="summary-item"><span>平均耗时</span><strong>${Math.round(this.summary.averageLatencyMs || 0)} ms</strong></div>
            </div>
        `;
    }

    syncCasesFromTable() {
        const rows = this.testCasesContainer.querySelectorAll('tbody tr');
        rows.forEach(row => {
            const index = Number(row.dataset.index);
            const nextCase = { ...this.testCases[index] };
            row.querySelectorAll('[data-field]').forEach(input => {
                const field = input.dataset.field;
                if (field === 'expectedKeywords' || field === 'expectedDocIds') {
                    nextCase[field] = this.splitList(input.value);
                } else {
                    nextCase[field] = input.value;
                }
            });
            this.testCases[index] = nextCase;
        });
    }

    normalizeImportedTestCases(parsed) {
        const rawCases = Array.isArray(parsed) ? parsed : parsed?.data || parsed?.testCases || parsed?.cases || [];
        if (!Array.isArray(rawCases)) {
            throw new Error('只支持 JSON 数组或包含 testCases/data 字段的 JSON 文件');
        }
        return rawCases
            .filter(item => item && typeof item === 'object')
            .map((item, index) => ({
                id: String(item.id || item.caseId || `case-${index + 1}`),
                question: String(item.question || ''),
                expectedAnswer: String(item.expectedAnswer || ''),
                expectedKeywords: this.normalizeList(item.expectedKeywords),
                expectedDocIds: this.normalizeList(item.expectedDocIds),
                source: String(item.source || 'imported'),
                metadata: item.metadata && typeof item.metadata === 'object' ? item.metadata : {}
            }))
            .filter(item => item.question.trim());
    }

    normalizeList(value) {
        if (Array.isArray(value)) {
            return value.map(item => String(item).trim()).filter(Boolean);
        }
        if (typeof value === 'string') {
            return this.splitList(value);
        }
        return [];
    }

    async postJson(path, body) {
        const response = await fetch(this.apiBaseUrl + path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const payload = await response.json();
        if (!response.ok || payload.code !== 200) {
            throw new Error(payload.message || '请求失败');
        }
        return payload;
    }

    async download(path, body, fallbackName) {
        try {
            const response = await fetch(this.apiBaseUrl + path, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            if (!response.ok) {
                throw new Error('导出失败');
            }
            const blob = await response.blob();
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = this.fileNameFromHeader(response.headers.get('Content-Disposition')) || fallbackName;
            document.body.appendChild(link);
            link.click();
            link.remove();
            URL.revokeObjectURL(url);
            this.showToast('导出完成');
        } catch (error) {
            this.showToast(error.message || '导出失败');
        }
    }

    setBusy(isBusy, text = '处理中...') {
        [this.generateBtn, this.runBtn, this.addCaseBtn].forEach(button => button.disabled = isBusy);
        if (isBusy) {
            this.generateBtn.textContent = text;
            this.runBtn.textContent = text;
        } else {
            this.generateBtn.textContent = '生成测试集';
            this.runBtn.textContent = '执行测试';
        }
    }

    showToast(message) {
        this.toast.textContent = message;
        this.toast.classList.add('show');
        setTimeout(() => this.toast.classList.remove('show'), 2600);
    }

    splitList(value) {
        return (value || '').split('|').map(item => item.trim()).filter(Boolean);
    }

    percent(value) {
        return `${Math.round((value || 0) * 100)}%`;
    }

    fileNameFromHeader(header) {
        if (!header) return '';
        const match = header.match(/filename\*=UTF-8''([^;]+)/) || header.match(/filename="?([^";]+)"?/);
        return match ? decodeURIComponent(match[1]) : '';
    }

    escapeHtml(value) {
        return String(value).replace(/[&<>"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[char]));
    }

    escapeAttr(value) {
        return this.escapeHtml(value).replace(/'/g, '&#39;');
    }
}

window.addEventListener('DOMContentLoaded', () => new RagTestApp());
